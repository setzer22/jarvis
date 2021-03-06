package edu.uoc.som.jarvis.language.execution;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.xtext.linking.impl.DefaultLinkingService;
import org.eclipse.xtext.linking.impl.IllegalNodeException;
import org.eclipse.xtext.nodemodel.INode;

import edu.uoc.som.jarvis.execution.ActionInstance;
import edu.uoc.som.jarvis.execution.ExecutionModel;
import edu.uoc.som.jarvis.execution.ExecutionPackage;
import edu.uoc.som.jarvis.execution.ExecutionRule;
import edu.uoc.som.jarvis.execution.ParameterValue;
import edu.uoc.som.jarvis.intent.EventDefinition;
import edu.uoc.som.jarvis.intent.Library;
import edu.uoc.som.jarvis.platform.ActionDefinition;
import edu.uoc.som.jarvis.platform.EventProviderDefinition;
import edu.uoc.som.jarvis.platform.Parameter;
import edu.uoc.som.jarvis.platform.PlatformDefinition;
import edu.uoc.som.jarvis.utils.ImportRegistry;

public class ExecutionLinkingService extends DefaultLinkingService {

	public ExecutionLinkingService() {
		super();
		System.out.println("Created Execution Linking Service");
	}

	@Override
	public List<EObject> getLinkedObjects(EObject context, EReference ref, INode node) throws IllegalNodeException {
		System.out.println("Linking context: " + context);
		System.out.println("Linking reference: " + ref);
		if (context instanceof ExecutionModel) {
			return getLinkedObjectsForExecutionModel((ExecutionModel) context, ref, node);
		} else if (context instanceof ExecutionRule) {
			return getLinkedObjectsForExecutionRule((ExecutionRule) context, ref, node);
		} else if (context instanceof ActionInstance) {
			return getLinkedObjectsForActionInstance((ActionInstance) context, ref, node);
		} else if (context instanceof ParameterValue) {
			return getLinkedObjectsForParameterValue((ParameterValue) context, ref, node);
		} else {
			return super.getLinkedObjects(context, ref, node);
		}
	}

	private List<EObject> getLinkedObjectsForExecutionModel(ExecutionModel context, EReference ref, INode node) {
		if (ref.equals(ExecutionPackage.eINSTANCE.getExecutionModel_EventProviderDefinitions())) {
			QualifiedName qualifiedName = getQualifiedName(node.getText());
			if (nonNull(qualifiedName)) {
				String platformName = qualifiedName.getQualifier();
				String eventProviderName = qualifiedName.getLocalName();
				PlatformDefinition platformDefinition = ImportRegistry.getInstance()
						.getImportedPlatform((ExecutionModel) context, platformName);
				EventProviderDefinition eventProviderDefinition = platformDefinition
						.getEventProviderDefinition(eventProviderName);
				if (nonNull(eventProviderDefinition)) {
					return Arrays.asList(eventProviderDefinition);
				}
			}
			return Collections.emptyList();
		} else {
			return super.getLinkedObjects(context, ref, node);
		}
	}

	private List<EObject> getLinkedObjectsForExecutionRule(ExecutionRule context, EReference ref, INode node) {
		if (ref.equals(ExecutionPackage.eINSTANCE.getExecutionRule_Event())) {
			ExecutionModel executionModel = (ExecutionModel) context.eContainer();
			/*
			 * Trying to retrieve an Event from a loaded Library
			 */
			EventDefinition foundEvent = getEventDefinitionFromImportedLibraries(executionModel, node.getText());
			if (isNull(foundEvent)) {
				/*
				 * Cannot retrieve the Event from a loaded Library, trying to retrieve it from a loaded Platform
				 */
				foundEvent = getEventDefinitionFromImportedPlatforms(executionModel, node.getText());
			}
			if (nonNull(foundEvent)) {
				return Arrays.asList(foundEvent);
			} else {
				/*
				 * Cannot retrieve the Event from the loaded Libraries or Platforms
				 */
				return Collections.emptyList();
			}
		} else {
			return super.getLinkedObjects(context, ref, node);
		}
	}

	private List<EObject> getLinkedObjectsForActionInstance(ActionInstance context, EReference ref, INode node) {
		if (ref.equals(ExecutionPackage.eINSTANCE.getActionInstance_Action())) {
			/*
			 * Trying to retrieve an Action from a loaded platform
			 */
			ExecutionModel executionModel = getContainingExecutionModel(context);
			QualifiedName qualifiedName = getQualifiedName(node.getText());
			if (nonNull(qualifiedName)) {
				String platformName = qualifiedName.getQualifier();
				String actionName = qualifiedName.getLocalName();
				PlatformDefinition platformDefinition = ImportRegistry.getInstance().getImportedPlatform(executionModel,
						platformName);
				/*
				 * Get the first one here, if there are multiple ActionDefinitions with the same name it will be rebound
				 * when setting its parameters.
				 */
				ActionDefinition actionDefinition = platformDefinition.getActions(actionName).get(0);
				if (nonNull(actionDefinition)) {
					return Arrays.asList(actionDefinition);
				}
			}
			return Collections.emptyList();
		} else {
			return super.getLinkedObjects(context, ref, node);
		}
	}

	private List<EObject> getLinkedObjectsForParameterValue(ParameterValue context, EReference ref, INode node) {
		if (ref.equals(ExecutionPackage.eINSTANCE.getParameterValue_Parameter())) {
			/*
			 * Trying to retrieve the Parameter of the containing Action
			 */
			ActionInstance actionInstance = (ActionInstance) context.eContainer();
			ActionDefinition actionDefinition = actionInstance.getAction();
			if (isNull(actionDefinition)) {
				/*
				 * TODO We should reload all the actions if this is not set
				 */
				System.out.println("Cannot retrieve the Action associated to " + actionInstance);
			}
			/*
			 * First look for the parameters in the defined containing Action. For platform containing multiple Actions
			 * with the same name (i.e. same JarvisAction but different constructors) this iteration can fail, because
			 * the inferred Action was not right.
			 */
			for (Parameter p : actionDefinition.getParameters()) {
				System.out.println("comparing Parameter " + p.getKey());
				System.out.println("Node text: " + node.getText());
				if (p.getKey().equals(node.getText())) {
					return Arrays.asList(p);
				}
			}
			/*
			 * Unable to find the Parameter in the inferred Action, trying to find alternative Actions with the same
			 * name and check their parameters. If such Action is found all the defined ParameterValues of this
			 * ActionInstance are processed and updated to fit the new parent Action.
			 */
			PlatformDefinition platformDefinition = (PlatformDefinition) actionDefinition.eContainer();
			if (isNull(platformDefinition)) {
				/*
				 * The platform may be null if there is an issue when loading the import. In that case we can ignore the
				 * linking, the model is false anyway
				 */
				return Collections.emptyList();
			}
			Parameter result = null;
			for (ActionDefinition platformAction : platformDefinition.getActions()) {
				if (!platformAction.equals(actionDefinition)
						&& platformAction.getName().equals(actionDefinition.getName())) {
					for (Parameter p : platformAction.getParameters()) {
						if (p.getKey().equals(node.getText())) {
							System.out.println("Found the parameter " + p.getKey() + " in a variant "
									+ actionDefinition.getName() + " returning it and updating the action instance");
							actionInstance.setAction(platformAction);
							result = p;
						}
					}
					if (nonNull(result)) {
						/*
						 * The Parameter was found in another Action, trying to reset all the ParameterValues' Parameter
						 * with the Action Parameters.
						 */
						ActionDefinition foundActionDefinition = (ActionDefinition) result.eContainer();
						boolean validAction = true;
						for (ParameterValue actionInstanceValue : actionInstance.getValues()) {
							Parameter actionInstanceParameter = actionInstanceValue.getParameter();
							boolean found = false;
							/*
							 * Check that each Parameter associated to the ActionInstance ParameterValues has a variant
							 * in the found action and update its reference. If all the Parameters have been updated the
							 * found Action variant is returned. Otherwise the loop searches for another Action variant.
							 */
							for (Parameter foundActionParameter : foundActionDefinition.getParameters()) {
								if (foundActionParameter.getKey().equals(actionInstanceParameter.getKey())) {
									actionInstanceValue.setParameter(foundActionParameter);
									found = true;
								}
							}
							validAction &= found;
						}
						if (validAction) {
							return Arrays.asList(result);
						}
					}
				}
			}
			return Collections.emptyList();
		} else {
			return super.getLinkedObjects(context, ref, node);
		}
	}

	/**
	 * Returns the {@link ExecutionModel} containing the provided {@code actionInstance}.
	 * <p>
	 * This method returns the first {@link ExecutionModel} instance in the provided {@code actionInstance}'s
	 * {@code eContainer} hierarchy. This method handles nested {@link ActionInstance}s (e.g. {@code onError}
	 * {@link ActionInstance}).
	 * 
	 * @param actionInstance the {@link ActionInstance} to retrieve the containing {@link ExecutionModel} from
	 * @return the containing {@link ExecutionModel} if it exists, {@code null} otherwise
	 */
	private ExecutionModel getContainingExecutionModel(ActionInstance actionInstance) {
		EObject currentObject = actionInstance;
		while (nonNull(currentObject)) {
			currentObject = currentObject.eContainer();
			if (currentObject instanceof ExecutionModel) {
				return (ExecutionModel) currentObject;
			}
		}
		return null;
	}

	private EventDefinition getEventDefinitionFromImportedLibraries(ExecutionModel executionModel,
			String eventDefinitionName) {
		Collection<Library> libraries = ImportRegistry.getInstance().getImportedLibraries(executionModel);
		for (Library library : libraries) {
			for (EventDefinition eventDefinition : library.getEventDefinitions()) {
				if (eventDefinition.getName().equals(eventDefinitionName)) {
					return eventDefinition;
				}
			}
		}
		return null;
	}

	private EventDefinition getEventDefinitionFromImportedPlatforms(ExecutionModel executionModel,
			String eventDefinitionName) {
		Collection<PlatformDefinition> platformDefinitions = ImportRegistry.getInstance()
				.getImportedPlatforms(executionModel);
		for (PlatformDefinition platformDefinition : platformDefinitions) {
			for (EventProviderDefinition eventProviderDefinition : platformDefinition.getEventProviderDefinitions()) {
				for (EventDefinition eventDefinition : eventProviderDefinition.getEventDefinitions()) {
					if (eventDefinition.getName().equals(eventDefinitionName)) {
						return eventDefinition;
					}
				}
			}
		}
		return null;
	}

	private QualifiedName getQualifiedName(String from) {
		String trimmed = from.trim();
		String[] splitted = trimmed.split("\\.");
		if (splitted.length != 2) {
			/*
			 * We don't handle qualified name that contain multiple or no qualifier.
			 */
			System.out.println(
					MessageFormat.format("Cannot compute a qualified name from the provided String {0}", from));
			return null;
		} else {
			return new QualifiedName(splitted[0], splitted[1]);
		}
	}
}
