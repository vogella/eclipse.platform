# Auto-refresh polling: allocation analysis and remaining work

Notes from investigating why `org.eclipse.core.internal.refresh.PollingMonitor` dominates allocation profiles.

## Where the allocation actually comes from

`PollingMonitor` itself allocates almost nothing.
Its entire cost is one line in `poll()` (`PollingMonitor.java:184`):

```java
if (resource.isSynchronized(IResource.DEPTH_INFINITE)) {
    return;
}
```

That builds a fresh `UnifiedTree` over the whole project and merges the workspace tree against a full directory scan, so a profiler attributes the whole walk to the poll job.

Per **directory** visited, in `UnifiedTree.addChildren` / `getLocalList` (`UnifiedTree.java:136-405`):

| Call | Allocation |
| --- | --- |
| `store.childInfos()` | one `FileInfo` plus name String per entry, plus the listing array |
| `container.members(...)` via `Container.getChildren` (`Container.java:139`) | `IPath[]` from ElementTree plus one `Resource` handle per child |
| `node.getStore()` via `FileStoreRoot.createStore` (`FileStoreRoot.java:130`) | IPath, `LocalFile`, `java.io.File`, absolute-path String |
| `Resource.filterChildren` (`UnifiedTree.java:396`) | see item 1 below |

Per **file** the visitor only compares `getLocalSyncInfo()` against `node.getLastModified()` (`RefreshLocalVisitor.java:305-310`), with no allocation.
`UnifiedTreeNode` instances are pooled through `UnifiedTree.freeNodes`, so they are not the problem either.

The result is O(workspace entries) of short-lived garbage per sweep, repeated indefinitely at a 4 s floor with a 5 % duty cycle.

## Done

### 1. Skip filter evaluation for projects without resource filters (PR #2884)

`Resource.filterChildren(IFileInfo[], boolean)` ran the full private overload even when a project had no filters at all: `getProjectRelativePath()`, two `LinkedList` allocations, then a `removeLastSegments(1)` walk to the project root allocating one IPath and taking one synchronized `ProjectDescription.getFilter` call per segment.

```java
final ProjectDescription description = project.internalGetDescription();
if (description == null || description.getFilters() == null) {
    return list;
}
```

`getFilters()` returns null exactly when the project has no filters, because `removeFilter` nulls the map once it empties (`ProjectDescription.java:840`), so the check is precise rather than a heuristic.
`isFilteredWithException` already guarded the same way.
Verified with `FilteredResourceTest` (28 tests).

### 2. Do not fill in a stack trace for ResourceChangedException (PR #2885)

`IsSynchronizedVisitor.ResourceChangedException` is a control-flow signal whose only catch site (`FileSystemResourceManager.java:845`) reads just `e.target` and never logs it.
Overriding `fillInStackTrace()` to return `this` avoids capturing a trace the class comment already calls meaningless.
Verified with `RefreshLocalTest` and `UnifiedTreeTest`.

## Rejected

### 3. Remove the list wrappers in getLocalList and addChildrenFromFileSystem

Would remove roughly four constant-size wrapper objects per directory: the `Arrays.asList` in `getLocalList`, the `Arrays.asList` around `container.members(...)`, and the `subList` plus its iterator in `addChildrenFromFileSystem`.

Not worth doing.
A directory with 20 entries already allocates 60 to 80 objects from the per-entry costs above, so this saves about 5 % of the per-directory constant and nothing per entry, in exchange for replacing two enhanced-for loops with index arithmetic.

## Remaining ideas, in decreasing value

### 4. Avoid rebuilding workspace child handles on every sweep

`Container.getChildren` allocates an `IPath[]` from ElementTree and then a fresh `Resource` handle per child, every sweep, purely so `addChildren` can compare names against the disk listing.
The comparison only needs names and types.
A name-and-type oriented accessor on `Container`, or reusing the ElementTree child IDs directly, would cut two objects per workspace entry per sweep.
Structural change, needs care around `isMember` flag filtering and the linked-resource branch in `addChildren`.

### 5. Avoid recreating the file store per directory

`FileStoreRoot.createStore` produces an IPath, a `LocalFile`, a `java.io.File` and an absolute-path String for every directory visited.
`ResourceInfo` already caches a `FileStoreRoot`; caching the derived store for the duration of a single `UnifiedTree` walk, or deriving child stores from the parent node's store instead of from the root, would remove most of this.
Note `UnifiedTreeNode.getStore()` is already lazy, so this only affects directories, not files.

### 6. Native refresh provider for Linux (the structural fix)

There is no native monitor on Linux at all.
The only `RefreshProvider` the Platform ships is `Win32RefreshProvider`, hardcoded in `MonitorManager.getRefreshProviders()`, and the `org.eclipse.core.resources.refreshProviders` extension point has no contributor in this repository.
So `MonitorManager.monitor()` reaches `pollMonitor.monitor(resource)` on every non-Windows platform by construction.
This is not inotify limits being exceeded; nothing tries to register a watch in the first place.

With a working provider installed, `RefreshManager` uses `pollMonitor.runOnce()` at startup only and the steady-state allocation goes to zero.

Implementation sketch and the hard parts:

- The API surface is small.
  Implement `RefreshProvider.installMonitor(IResource, IRefreshResult, IProgressMonitor)` returning an `IRefreshMonitor` (a single `unmonitor(IResource)` method).
  Report changes with `result.refresh(resource)` and give up on a resource with `result.monitorFailed(monitor, resource)`, which hands it back to `PollingMonitor`.
  That fallback already exists and is exercised, so a partial implementation degrades safely.
  `Win32Monitor` (668 lines) is a useful shape reference.
- `WatchService` is not recursive on Linux.
  Every directory needs its own registration, plus register and unregister as directories appear and disappear, racing the filesystem.
  This is where `fs.inotify.max_user_watches` actually bites.
  Exhausting it should produce `monitorFailed` for that project.
- `StandardWatchEventKinds.OVERFLOW` means events were dropped with no indication of which.
  The only correct response is a full refresh of the affected subtree, so the `UnifiedTree` walk stays as a recovery path.
- macOS ships the JDK `PollingWatchService`, which polls internally without the 5 % duty cycle or hot-root heuristic, so a naive provider would be worse than today.
  Keep the provider Linux-only unless someone binds FSEvents.
- Bursts from builds or `git checkout` need debouncing and batching to a common ancestor before calling `result.refresh`, otherwise the allocation just moves into the refresh job along with workspace locking.
- `WatchService` does not follow symbolic links.
  The existing `UnifiedTree` code has real machinery for this (`isRecursiveLink`, `PrefixPool`); a watch-based provider misses changes behind linked folders unless it registers their targets and owns cycle detection itself.

Rough estimate: two to three days for a Linux-only provider that falls back cleanly, considerably more to make it trustworthy enough to enable by default.

A useful intermediate step: let the provider handle only the projects it can register within the watch budget and report `monitorFailed` for the rest.
`MonitorManager` already supports that mix per resource.
