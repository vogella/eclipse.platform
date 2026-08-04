# Plan: stop builds from blocking unrelated user actions

Implementation plan for the problem tracked in
[bug 329657](https://bugs.eclipse.org/bugs/show_bug.cgi?id=329657) /
[eclipse.platform.ui#472](https://github.com/eclipse-platform/eclipse.platform.ui/issues/472),
and an assessment of the attempted fix in
[eclipse.platform#1743](https://github.com/eclipse-platform/eclipse.platform/pull/1743).

All line references are against `origin/master` at `1ab1f7ebdf`.

## The problem

While a build runs, the user cannot save a file, even a file in a completely unrelated project.
The save blocks until the build finishes, and the save then immediately triggers another build.
Reporters describe this as the single largest annoyance in daily Eclipse use.

## Why PR 1743 is not the fix

PR 1743 adds `LockListener.isUI()` and changes `ThreadJob.joinRun` to

```java
if (manager.getLockManager().aboutToWait(blocker) || manager.getLockManager().isUI()) {
    return threadJob;
}
```

Because `UILockListener.canBlock()` is already `!isUI()`, the effect is that the UI thread never waits for a scheduling rule again.
It returns from `joinRun` without ever having acquired the rule.

Five problems with that.

**It does not compile downstream.**
`UILockListener.isUI()` in `org.eclipse.ui.internal` is package private.
Adding a `public boolean isUI()` to `LockListener` turns that into an illegal reduced visibility override, so `org.eclipse.ui.workbench` no longer compiles.
This repository's CI cannot see that; the aggregator build would.
If the method is instead made public in `UILockListener`, the new API contract ends up being satisfied by an accidental name collision rather than by design.

**The null guard is missing.**
`LockManager.isUI()` lacks the `lockListener == null` check that `aboutToWait` and `canBlock` both have.
Headless means an NPE on every contended `beginRule`, swallowed into `RuntimeLog` via `LockManager.handleException` (`LockManager.java:185`).
That is the most likely source of the CI failures on the PR.

**It silently disables the mechanism built for this exact problem.**
`joinRun` returns before `waitForRun`, so `implicitJobs.addWaiting(threadJob)` never runs.
`JobManager.isBlocking()` scans `waitingThreadJobs` (`JobManager.java:962`) to decide whether a running job is blocking somebody, and `AutoBuildJob.isInterrupted()` (`AutoBuildJob.java:265`) calls exactly that in order to self interrupt.
With the patch applied, the autobuild can no longer tell that anyone is waiting on it.

**It can produce silently wrong incremental builds.**
`BuildManager.basicBuild` deliberately drops the workspace lock before invoking builders (`BuildManager.java:288`, `beginUnprotected()`).
Inside that window the scheduling rule is the only thing holding the workspace still for the builder.
Let the UI thread through and the `finally` block at `BuildManager.java:328-330` records `workspace.getElementTree()` as `lastBuiltTree`.
A save that landed mid builder is then baked into the last built state without ever having appeared in a delta the builder saw, so the next incremental build never compiles that edit.
Stale output that only a clean build fixes is a worse bug than waiting.
The deadlock detector is also never told about the acquisition, since `LockManager.addLockThread` is skipped, so deadlock recovery degrades as well.

**As merged it is a no op.**
Nothing in this repository overrides `isUI()`, so it always returns `false`.
The behaviour under review is neither visible in nor testable from this PR.

On the general principle: `aboutToWait` returning `true` is safe only because the UI thread is servicing a `syncExec` from the rule owner, so the owner is effectively lending its rule for the duration.
There is no safe generalisation of that to "the UI thread never waits".

## Root cause

Four findings, all in `org.eclipse.core.resources`.

**(a) The builder rule defaults to the workspace root.**
`IncrementalProjectBuilder.getRule(int, Map)` returns `ResourcesPlugin.getWorkspace().getRoot()` (`IncrementalProjectBuilder.java:534`).
Every builder that does not override it locks the entire workspace.

**(b) Overriding it buys nothing under autobuild.**
This is the unanswered report from travkin79 in issue #472, and it is correct.
`AutoBuildJob.doBuild` acquires `getRuleFactory().buildRule()`, which is the workspace root (`Rules.java:57`), around the whole build.
The per builder `beginRule(builderRule)` at `BuildManager.java:290` is nested inside that, and a nested rule cannot narrow the outer one.
So `getRule()` is effectively dead API for the autobuild case, which is the case users hit all day.

**(c) The relaxed path already exists but is unreachable where it matters.**
Bug 343256 added it: `Workspace.buildInternal` releases the root rule after `PRE_BUILD` and runs the build with a `null` rule when all configurations are relaxed (`Workspace.java:525`, `532-547`, `618-628`).
It is gated on `requestedConfigs.length > 0`, so "Build All" and "Clean All" never relax because they pass `EMPTY_BUILD_CONFIG_ARRAY`.
For the full workspace path it is only recomputed when parallel builds are enabled (`Workspace.java:604-607`).
Autobuild bypasses `buildInternal` entirely.

**(d) Interrupt is coarse and destructive.**
`BuildManager.checkCanceled` returns early for anything that is not `AUTO_BUILD` (`BuildManager.java:729`), so a manual build yields to nobody.
That is precisely the "edit a file while the workspace is being clean built" scenario from the issue.
When interrupt does fire it discards the whole build and reschedules, which is the "and then the projects immediately rebuild" complaint from the very first comment on the issue.

## Implementation plan

### Step 1: let autobuild and full workspace builds use the relaxed path

Highest value, fully contained in `org.eclipse.core.resources`, no Jobs API change, no weakening of any rule invariant.

Extract the acquire root for `PRE_BUILD`, drop to `null`, re-acquire root for `POST_BUILD` sequence out of `Workspace.buildInternal` (`Workspace.java:532-547` and `618-628`) into a single helper.
Call that helper from `AutoBuildJob.doBuild` so the autobuild stops holding the workspace root across `BuildManager.build(...)`.
Drop the `requestedConfigs.length > 0` gate at `Workspace.java:525` so `EMPTY_BUILD_CONFIG_ARRAY` can relax as well, guarded by `allRelaxed(getBuildOrder(), trigger)`.

Points to get right:

- The relaxed decision has to be recomputed after `PRE_BUILD`, because a `PRE_BUILD` listener may change the build order or the build spec.
  `buildInternal` already computes the build order after the notification for this reason.
- `AutoBuildJob` itself has `setRule(workspace.getRoot())` in its constructor (`AutoBuildJob.java:62`).
  The job rule and the operation rule are separate.
  Leaving the job rule at the root keeps autobuild serialised against other builds, which is wanted; only the operation rule needs to relax.
  Verify that the nested `beginRule` inside `basicBuild` still behaves once the outer operation rule is `null`, since `ImplicitJobs.begin` takes the real job's rule when the job has one.
  If the job rule alone is enough to keep blocking, the job rule has to relax too, and the serialisation has to come from a job family wait instead.
- `endOperation`/`prepareOperation` pairing in `doBuild` must stay balanced on every exit path, including `OperationCanceledException` from an interrupt.

Effect: a builder that declares a narrow rule stops blocking edits outside that rule, for autobuild and for Build All.
`IncrementalProjectBuilder.getRule()` starts meaning what its javadoc has claimed since 3.6.

### Step 2: make the default rule useful

Step 1 changes nothing until builders actually declare narrow rules.

Flipping the default in `IncrementalProjectBuilder.getRule` is not safe, because third party builders silently rely on the workspace standing still.
Two lower risk moves:

- Have the platform's own builders, and the big external ones (JDT Core, PDE, m2e), return a `MultiRule` of the project plus its prerequisite projects.
  In a multi project workspace that alone removes most of the pain, because edits in unrelated projects stop blocking.
- Add a declarative opt in on the `org.eclipse.core.resources.builders` extension point, so a builder can be marked relaxed without a code change and without subclassing gymnastics.

### Step 3: yield instead of abort

`Job.yieldRule(IProgressMonitor)` exists (`Job.java:964`) and `org.eclipse.core.resources` never uses it.

In `BuildManager.basicBuildLoop`, between builders, when `autoBuildJob.isBlocking()`, yield the rule and resume rather than throwing `OperationCanceledException`.
That preserves the build state already computed instead of triggering the rebuild storm, and it works for manual builds too, so the `trigger != IncrementalProjectBuilder.AUTO_BUILD` early return at `BuildManager.java:729` can go.

The caveat to design around: the yield has to happen inside a `beginUnprotected` window.
Otherwise the thread that receives the rule immediately blocks on the `WorkManager` lock and nothing is gained.
`Job.yieldRule` explicitly does not release other locks held by the job.

### Step 4: the residual stall inside one builder

Nothing in the platform can preempt a single long running `builder.build()` call.
Two things that help:

- Give `IncrementalProjectBuilder` a protected yield or checkpoint hook that delegates to the enclosing job, so JDT and m2e can cooperate at safe points.
- Name the culprit in the blocked progress UI.
  `BuildManager` already knows via `currentBuilders` and `hookStartBuild`, so the blocking dialog can say which builder holds the rule.
  Users then report against the right component instead of against Platform.

### Step 5: the salvageable part of PR 1743's intent, on the UI side

Typing in an editor needs no scheduling rule at all; only the save does.
The right fix for a blocked Ctrl+S is to run the save as a `Job` with `modifyRule(file)` and let it wait with progress, so the UI stays live while the save waits its turn.
That belongs in `eclipse.platform.ui` (`SaveableHelper`, `WorkspaceModifyOperation`), not in `ThreadJob`.

## Test plan

Step 1 is testable headlessly in `org.eclipse.core.tests.resources`, which is what the PR was asked for and never got.

Install a builder on project A whose `getRule` returns project A.
Enable autobuild and block inside the builder on a latch.
From another thread, call `beginRule(ruleFactory.modifyRule(fileInProjectB))` and assert it returns within a short timeout.
Release the latch and assert the build completes normally.

This fails today and passes after Step 1, and it needs no display.

Add a second variant with the builder rule left at the default (workspace root) to assert that the conservative behaviour is unchanged, so the test documents both sides of the contract.

For Step 3, assert that a yield preserves `lastBuiltTree`, meaning the follow up build is incremental rather than full.

## Sequencing

1. Step 1 plus its test, as one self contained PR against `eclipse.platform`.
   It is the only change that is both high value and low risk, and it unblocks everything else.
2. Step 3, once Step 1 has soaked, because it touches the interrupt semantics that several tests depend on.
3. Step 2 in parallel, as separate PRs per builder owner, since it is mostly not platform code.
4. Steps 4 and 5 last, as they are API additions and cross repository.

## Open questions

- Does relaxing the autobuild operation rule require relaxing `AutoBuildJob`'s own job rule as well, or is the operation rule sufficient?
  This decides whether Step 1 stays a small change or grows a job family based serialisation.
- Does any `PRE_BUILD` or `POST_BUILD` listener rely on the workspace root rule being held continuously across the build?
  The notifications themselves keep it, but a listener that spawns work assuming the rule persists would break.
- Is a workspace preference wanted as a safety valve for Step 1, so a user hitting a badly behaved builder can restore the old conservative behaviour?
