/*******************************************************************************
 * Copyright (C) 2014 Robin Stocker <robin@nibor.org> and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.ui.internal.selection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.mapping.ResourceMapping;
import org.eclipse.core.resources.mapping.ResourceTraversal;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.egit.core.AdapterUtils;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.internal.CommonUtils;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.egit.ui.internal.revision.FileRevisionEditorInput;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.team.core.history.IFileRevision;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.ResourceUtil;

/**
 * Utilities for working with selections.
 */
public class SelectionUtils {

	/**
	 * @param selection
	 * @return the single selected repository, or <code>null</code>
	 */
	public static Repository getRepository(IStructuredSelection selection) {
		return getRepository(false, selection, null);
	}

	/**
	 * @param evaluationContext
	 * @return the single selected repository, or <code>null</code>
	 */
	public static Repository getRepository(IEvaluationContext evaluationContext) {
		return getRepository(false, getSelection(evaluationContext), null);
	}

	/**
	 * Get the single selected repository or warn if no repository or multiple
	 * different repositories could be found.
	 *
	 * @param selection
	 * @param shell
	 *            the shell for showing the warning
	 * @return the single selected repository, or <code>null</code>
	 */
	public static Repository getRepositoryOrWarn(
			IStructuredSelection selection, Shell shell) {
		return getRepository(true, selection, shell);
	}

	/**
	 * @param context
	 * @return the structured selection of the evaluation context
	 */
	public static IStructuredSelection getSelection(IEvaluationContext context) {
		if (context == null)
			return StructuredSelection.EMPTY;

		Object selection = context
				.getVariable(ISources.ACTIVE_MENU_SELECTION_NAME);
		if (!(selection instanceof ISelection))
			selection = context
					.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);

		if (selection instanceof ITextSelection)
			return getSelectionFromEditorInput(context);
		else if (selection instanceof IStructuredSelection)
			return (IStructuredSelection) selection;
		return StructuredSelection.EMPTY;
	}

	/**
	 * Tries to convert the passed selection to a structured selection.
	 * <p>
	 * E.g. in case of a text selection, it is converted to be a selection of
	 * the resource that is in the editor.
	 *
	 * @param selection
	 * @return the structured selection, or an empty selection
	 */
	public static IStructuredSelection getStructuredSelection(
			ISelection selection) {
		if (selection instanceof ITextSelection)
			return getSelectionFromEditorInput(getEvaluationContext());
		else if (selection instanceof IStructuredSelection)
			return (IStructuredSelection) selection;
		return StructuredSelection.EMPTY;
	}

	/**
	 * @param selection
	 * @return the selected locations
	 */
	public static IPath[] getSelectedLocations(IStructuredSelection selection) {
		Set<IPath> result = new LinkedHashSet<IPath>();
		for (Object o : selection.toList()) {
			IResource resource = AdapterUtils.adapt(o, IResource.class);
			if (resource != null) {
				IPath location = resource.getLocation();
				if (location != null)
					result.add(location);
			} else {
				IPath location = AdapterUtils.adapt(o, IPath.class);
				if (location != null)
					result.add(location);
				else
					for (IResource r : extractResourcesFromMapping(o)) {
						IPath l = r.getLocation();
						if (l != null)
							result.add(l);
					}
			}
		}
		return result.toArray(new IPath[result.size()]);
	}

	/**
	 * @param selection
	 * @return the resources in the selection
	 */
	public static IResource[] getSelectedResources(
			IStructuredSelection selection) {
		Set<IResource> result = new LinkedHashSet<IResource>();
		for (Object o : selection.toList()) {
			IResource resource = AdapterUtils.adapt(o, IResource.class);
			if (resource != null)
				result.add(resource);
			else
				result.addAll(extractResourcesFromMapping(o));
		}
		return result.toArray(new IResource[result.size()]);
	}

	private static List<IResource> extractResourcesFromMapping(Object o) {
		ResourceMapping mapping = AdapterUtils.adapt(o, ResourceMapping.class);
		if (mapping == null)
			return Collections.emptyList();

		ResourceTraversal[] traversals;
		try {
			traversals = mapping.getTraversals(null, null);
		} catch (CoreException e) {
			Activator.logError(e.getMessage(), e);
			return Collections.emptyList();
		}

		if (traversals.length == 0)
			return Collections.emptyList();

		List<IResource> result = new ArrayList<IResource>();
		for (ResourceTraversal traversal : traversals) {
			IResource[] resources = traversal.getResources();
			result.addAll(Arrays.asList(resources));
		}
		return result;
	}

	/**
	 * Figure out which repository to use. All selected resources must map to
	 * the same Git repository.
	 *
	 * @param warn
	 *            Put up a message dialog to warn why a resource was not
	 *            selected
	 * @param selection
	 * @param shell
	 *            must be provided if warn = true
	 * @return repository for current project, or null
	 */
	private static Repository getRepository(boolean warn,
			IStructuredSelection selection, Shell shell) {
		RepositoryMapping mapping = null;
		for (IPath location : getSelectedLocations(selection)) {
			RepositoryMapping repositoryMapping = RepositoryMapping
					.getMapping(location);
			if (mapping == null)
				mapping = repositoryMapping;
			if (repositoryMapping == null)
				return null;
			if (mapping.getRepository() != repositoryMapping.getRepository()) {
				if (warn)
					MessageDialog.openError(shell,
							UIText.RepositoryAction_multiRepoSelectionTitle,
							UIText.RepositoryAction_multiRepoSelection);
				return null;
			}
		}
		Repository result = null;
		if (mapping == null)
			for (Object o : selection.toArray()) {
				Repository nextRepo = null;
				if (o instanceof Repository)
					nextRepo = (Repository) o;
				else if (o instanceof PlatformObject)
					nextRepo = (Repository) ((PlatformObject) o)
							.getAdapter(Repository.class);
				if (nextRepo != null && result != null
						&& !result.equals(nextRepo)) {
					if (warn)
						MessageDialog
								.openError(
										shell,
										UIText.RepositoryAction_multiRepoSelectionTitle,
										UIText.RepositoryAction_multiRepoSelection);
					return null;
				}
				result = nextRepo;
			}
		else
			result = mapping.getRepository();
		if (result == null) {
			if (warn)
				MessageDialog.openError(shell,
						UIText.RepositoryAction_errorFindingRepoTitle,
						UIText.RepositoryAction_errorFindingRepo);
			return null;
		}

		return result;
	}

	private static IStructuredSelection getSelectionFromEditorInput(
			IEvaluationContext context) {
		Object object = context.getVariable(ISources.ACTIVE_EDITOR_INPUT_NAME);
		if (!(object instanceof IEditorInput)) {
			Object editor = context.getVariable(ISources.ACTIVE_EDITOR_NAME);
			if (editor instanceof IEditorPart)
				object = ((IEditorPart) editor).getEditorInput();
		}

		if (object instanceof IEditorInput) {
			IEditorInput editorInput = (IEditorInput) object;
			// Note that there is both a getResource(IEditorInput) as well as a
			// getResource(Object), which don't do the same thing. We explicitly
			// want the first here.
			IResource resource = ResourceUtil.getResource(editorInput);
			if (resource != null)
				return new StructuredSelection(resource);
			if (editorInput instanceof FileRevisionEditorInput) {
				FileRevisionEditorInput fileRevisionEditorInput = (FileRevisionEditorInput) editorInput;
				IFileRevision fileRevision = fileRevisionEditorInput
						.getFileRevision();
				if (fileRevision != null)
					return new StructuredSelection(fileRevision);
			}
		}

		return StructuredSelection.EMPTY;
	}

	private static IEvaluationContext getEvaluationContext() {
		IEvaluationContext ctx;
		IWorkbenchWindow activeWorkbenchWindow = PlatformUI.getWorkbench()
				.getActiveWorkbenchWindow();
		// no active window during Eclipse shutdown
		if (activeWorkbenchWindow == null)
			return null;
		IHandlerService hsr = CommonUtils.getService(activeWorkbenchWindow, IHandlerService.class);
		ctx = hsr.getCurrentState();
		return ctx;
	}

}
