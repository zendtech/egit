/*******************************************************************************
 * Copyright (C) 2010, 2013 Dariusz Luksza <dariusz@luksza.org> and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.core.synchronize.dto;

import static org.eclipse.core.runtime.Assert.isNotNull;
import static org.eclipse.egit.core.RevUtils.getCommonAncestor;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_BRANCH_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_MERGE;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_REMOTE;
import static org.eclipse.jgit.lib.Constants.R_HEADS;
import static org.eclipse.jgit.lib.Constants.R_REMOTES;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/**
 * Simple data transfer object containing all necessary information for
 * launching synchronization
 */
public class GitSynchronizeData {

	private static final IWorkspaceRoot ROOT = ResourcesPlugin.getWorkspace()
					.getRoot();

	/**
	 * Matches all strings that start from R_HEADS
	 */
	public static final Pattern BRANCH_NAME_PATTERN = Pattern.compile("^" + R_HEADS + ".*?"); //$NON-NLS-1$ //$NON-NLS-2$

	private final boolean includeLocal;

	private final Repository repo;

	private final String srcRemote;

	private final String dstRemote;

	private final String srcMerge;

	private final String dstMerge;

	private RevCommit srcRevCommit;

	private RevCommit dstRevCommit;

	private RevCommit ancestorRevCommit;

	private final Set<IProject> projects;

	private final String repoParentPath;

	private final String srcRev;

	private final String dstRev;

	private TreeFilter pathFilter;

	private Set<IResource> includedResources;

	private static class RemoteConfig {
		final String remote;
		final String merge;
		public RemoteConfig(String remote, String merge) {
			this.remote = remote;
			this.merge = merge;
		}
	}

	/**
	 * Constructs {@link GitSynchronizeData} object for all resources.
	 * Equivalent to
	 * <code>new GitSynchronizeData(repository, srcRev, dstRev, includeLocal, null)</code>
	 * .
	 *
	 * @param repository
	 * @param srcRev
	 * @param dstRev
	 * @param includeLocal
	 *            <code>true</code> if local changes should be included in
	 *            comparison
	 * @throws IOException
	 */
	public GitSynchronizeData(Repository repository, String srcRev,
			String dstRev, boolean includeLocal) throws IOException {
		this(repository, srcRev, dstRev, includeLocal, null);
	}

	/**
	 * Constructs a {@link GitSynchronizeData} object while restricting it to a
	 * set of resources.
	 *
	 * @param repository
	 * @param srcRev
	 * @param dstRev
	 * @param includeLocal
	 * @param includedResources
	 *            either the set of resources to include in synchronization or
	 *            {@code null} to synchronize all resources.
	 * @throws IOException
	 */
	public GitSynchronizeData(Repository repository, String srcRev,
			String dstRev, boolean includeLocal,
			Set<IResource> includedResources) throws IOException {
		isNotNull(repository);
		isNotNull(srcRev);
		isNotNull(dstRev);
		this.repo = repository;
		this.srcRev = srcRev;
		this.dstRev = dstRev;
		this.includeLocal = includeLocal;

		RemoteConfig srcRemoteConfig = extractRemoteName(srcRev);
		RemoteConfig dstRemoteConfig = extractRemoteName(dstRev);

		srcRemote = srcRemoteConfig.remote;
		srcMerge = srcRemoteConfig.merge;

		dstRemote = dstRemoteConfig.remote;
		dstMerge = dstRemoteConfig.merge;

		repoParentPath = repo.getDirectory().getParentFile().getAbsolutePath();

		projects = new HashSet<IProject>();
		final Iterable<? extends IResource> includedResourceIterable;
		if (includedResources == null)
			// include all project in synchronization
			includedResourceIterable = Arrays.asList(ROOT.getProjects());
		else
			includedResourceIterable = includedResources;
		for (IResource res : includedResourceIterable) {
			IProject project = res.getProject();
			RepositoryMapping mapping = RepositoryMapping.getMapping(project);
			if (mapping != null && mapping.getRepository() == repo)
				projects.add(project);
		}

		// do not set field if includedResources is null, some methods expect
		// #getIncludedResources() to return <null> to know it should
		// synchronize all resources.
		if (includedResources != null)
			setIncludedResources(includedResources);

		updateRevs();
	}

	/**
	 * Recalculates source, destination and ancestor Rev commits
	 *
	 * @throws IOException
	 */
	public void updateRevs() throws IOException {
		ObjectWalk ow = new ObjectWalk(repo);
		try {
			srcRevCommit = getCommit(srcRev, ow);
			dstRevCommit = getCommit(dstRev, ow);
		} finally {
			ow.release();
		}

		if (this.dstRevCommit != null && this.srcRevCommit != null)
			this.ancestorRevCommit = getCommonAncestor(repo, this.srcRevCommit,
					this.dstRevCommit);
		else
			this.ancestorRevCommit = null;
	}

	/**
	 * @return instance of repository that should be synchronized
	 */
	public Repository getRepository() {
		return repo;
	}

	/**
	 * @return name of source remote or {@code null} when source branch is not a
	 *         remote branch
	 */
	public String getSrcRemoteName() {
		return srcRemote;
	}

	/**
	 * @return ref specification of destination merge branch
	 */
	public String getDstMerge() {
		return dstMerge;
	}

	/**
	 * @return ref specification of source merge branch
	 */
	public String getSrcMerge() {
		return srcMerge;
	}

	/**
	 * @return name of destination remote or {@code null} when destination
	 *         branch is not a remote branch
	 */
	public String getDstRemoteName() {
		return dstRemote;
	}

	/**
	 * @return synchronize source rev name
	 */
	public RevCommit getSrcRevCommit() {
		return srcRevCommit;
	}

	/**
	 * @return synchronize destination rev name
	 */
	public RevCommit getDstRevCommit() {
		return dstRevCommit;
	}

	/**
	 * @return list of project's that are connected with this repository
	 */
	public Set<IProject> getProjects() {
		return Collections.unmodifiableSet(projects);
	}

	/**
	 * @param file
	 * @return <true> if given {@link File} is contained by this repository
	 */
	public boolean contains(File file) {
		return file.getAbsoluteFile().toString().startsWith(repoParentPath);
	}

	/**
	 * @return <code>true</code> if local changes should be included in
	 *         comparison
	 */
	public boolean shouldIncludeLocal() {
		return includeLocal;
	}

	/**
	 * @return common ancestor commit
	 */
	public RevCommit getCommonAncestorRev() {
		return ancestorRevCommit;
	}

	/**
	 * @param includedResources
	 *            list of resources to be synchronized
	 */
	public void setIncludedResources(Set<IResource> includedResources) {
		this.includedResources = includedResources;
		Set<String> paths = new HashSet<String>();
		RepositoryMapping rm = RepositoryMapping.findRepositoryMapping(repo);
		for (IResource resource : includedResources) {
			String repoRelativePath = rm.getRepoRelativePath(resource);
			if (repoRelativePath != null && repoRelativePath.length() > 0)
				paths.add(repoRelativePath);
		}

		if (!paths.isEmpty())
			pathFilter = PathFilterGroup.createFromStrings(paths);
	}

	/**
	 * @return set of included resources or {@code null} when all resources
	 *         should be included
	 */
	public Set<IResource> getIncludedResources() {
		return includedResources;
	}

	/**
	 * Disposes all nested resources
	 */
	public void dispose() {
		if (projects != null)
			projects.clear();
		if (includedResources != null)
			includedResources.clear();
	}

	/**
	 * @return instance of {@link TreeFilter} when synchronization was launched
	 *         from nested node (like folder) or {@code null} otherwise
	 */
	public TreeFilter getPathFilter() {
		return pathFilter;
	}

	/**
	 * @return synchronization source rev
	 */
	public String getSrcRev() {
		return srcRev;
	}

	/**
	 * @return synchronization destination rev
	 */
	public String getDstRev() {
		return dstRev;
	}

	private RemoteConfig extractRemoteName(String rev) {
		if (rev.contains(R_REMOTES)) {
			String remoteWithBranchName = rev.replaceAll(R_REMOTES, ""); //$NON-NLS-1$
			int firstSeparator = remoteWithBranchName.indexOf("/"); //$NON-NLS-1$

			String remote = remoteWithBranchName.substring(0, firstSeparator);
			String name = remoteWithBranchName.substring(firstSeparator + 1,
					remoteWithBranchName.length());

			return new RemoteConfig(remote, R_HEADS + name);
		} else {
			String realName;
			Ref ref;
			try {
				ref = repo.getRef(rev);
			} catch (IOException e) {
				ref = null;
			}
			if (ref != null && ref.isSymbolic())
				realName = ref.getTarget().getName();
			else
				realName = rev;
			String name = BRANCH_NAME_PATTERN.matcher(realName).replaceAll(""); //$NON-NLS-1$
			String remote = repo.getConfig().getString(CONFIG_BRANCH_SECTION,
					name, CONFIG_KEY_REMOTE);
			String merge = repo.getConfig().getString(CONFIG_BRANCH_SECTION,
					name, CONFIG_KEY_MERGE);

			return new RemoteConfig(remote, merge);
		}
	}

	private RevCommit getCommit(String rev, ObjectWalk ow) throws IOException {
		if (rev.length() > 0) {
			ObjectId id = repo.resolve(rev);
			return id != null ? ow.parseCommit(id) : null;
		} else
			return null;
	}

}
