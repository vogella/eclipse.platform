package org.eclipse.team.internal.ccvs.core;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
 
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.team.internal.ccvs.core.client.Command.LocalOption;

 /**
  * This interface represents a remote folder in a repository. It provides
  * access to the members (remote files and folders) of a remote folder
  * 
  * Clients are not expected to implement this interface.
  */
public interface ICVSRemoteFolder extends ICVSRemoteResource, ICVSFolder {
	
	/**
	 * Return the context of this handle. The returned tag can be a branch or
	 * version tag.
	 */
	public CVSTag getTag();
	
	/**
	 * Return the local options that are used to determine how memebers are retrieved.
	 * 
	 * Interesting options are:
	 *     Checkout.ALIAS
	 *     Command.DO_NOT_RECURSE
	 */
	public LocalOption[] getLocalOptions();
	
	/**
	 * Indicates whether the remote folder can be expanded. 
	 * 
	 * This is a temporary (hopefully) means of indicating certain types of folders 
	 * (i.e. module definitions) that are not expandable due to lack of mdoule expansion.
	 * They can still be checked out.
	 */
	public boolean isExpandable();
}