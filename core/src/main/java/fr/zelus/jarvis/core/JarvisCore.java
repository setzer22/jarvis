package fr.zelus.jarvis.core;

import com.google.cloud.dialogflow.v2.SessionName;
import fr.inria.atlanmod.commons.log.Log;
import fr.zelus.jarvis.core.session.JarvisSession;
import fr.zelus.jarvis.dialogflow.DialogFlowApi;
import fr.zelus.jarvis.dialogflow.DialogFlowException;
import fr.zelus.jarvis.intent.Context;
import fr.zelus.jarvis.intent.ContextParameterValue;
import fr.zelus.jarvis.intent.IntentPackage;
import fr.zelus.jarvis.intent.RecognizedIntent;
import fr.zelus.jarvis.io.InputProvider;
import fr.zelus.jarvis.module.Action;
import fr.zelus.jarvis.module.InputProviderDefinition;
import fr.zelus.jarvis.module.Module;
import fr.zelus.jarvis.module.ModulePackage;
import fr.zelus.jarvis.orchestration.ActionInstance;
import fr.zelus.jarvis.orchestration.OrchestrationLink;
import fr.zelus.jarvis.orchestration.OrchestrationModel;
import fr.zelus.jarvis.orchestration.OrchestrationPackage;
import fr.zelus.jarvis.util.Loader;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static fr.inria.atlanmod.commons.Preconditions.checkNotNull;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * The core component of the jarvis framework.
 * <p>
 * This class is constructed from an {@link OrchestrationModel}, that defines the Intent to Action bindings that are
 * executed by the application. Constructing an instance of this class will load the {@link JarvisModule}s used by
 * the provided {@link OrchestrationModel}, and enable the corresponding {@link JarvisAction}s. It also creates an
 * instance of {@link IntentDefinitionRegistry} that can be accessed to retrieve and manage
 * {@link fr.zelus.jarvis.intent.IntentDefinition} .
 *
 * @see IntentDefinitionRegistry
 * @see JarvisModuleRegistry
 * @see OrchestrationService
 * @see JarvisModule
 */
public class JarvisCore {

    /**
     * The {@link Configuration} key to store the unique identifier of the DialogFlow project.
     *
     * @see #JarvisCore(Configuration)
     */
    public static String PROJECT_ID_KEY = "dialogflow.projectId";

    /**
     * The {@link Configuration} key to store the code of the language processed by DialogFlow.
     *
     * @see #JarvisCore(Configuration)
     */
    public static String LANGUAGE_CODE_KEY = "dialogflow.language";

    /**
     * The {@link Configuration} key to store the {@link OrchestrationModel} to use.
     *
     * @see #JarvisCore(Configuration)
     */
    public static String ORCHESTRATION_MODEL_KEY = "jarvis.orchestration.model";

    /**
     * Builds a {@link Configuration} holding the provided {@code projectId}, {@code languageCode}, and {@code
     * orchestrationModel}.
     * <p>
     * This method is called by {@link #JarvisCore(String, String, OrchestrationModel)} to setup the
     * {@link Configuration} that is forwarded to the base constructor {@link #JarvisCore(Configuration)}.
     *
     * @param projectId          the unique identifier of the DialogFlow project
     * @param languageCode       the code of the language processed by DialogFlow
     * @param orchestrationModel the {@link OrchestrationModel} defining the Intent to Action bindings
     * @return the {@link Configuration} holding the provided {@code projectId}, {@code languageCode}, and {@code
     * orchestrationModel}
     * @see #JarvisCore(Configuration)
     * @see #JarvisCore(String, String, OrchestrationModel)
     */
    protected static Configuration buildConfiguration(String projectId, String languageCode, OrchestrationModel
            orchestrationModel) {
        Configuration configuration = new BaseConfiguration();
        configuration.addProperty(PROJECT_ID_KEY, projectId);
        configuration.addProperty(LANGUAGE_CODE_KEY, languageCode);
        configuration.addProperty(ORCHESTRATION_MODEL_KEY, orchestrationModel);
        return configuration;
    }

    /**
     * The {@link Configuration} used to initialize this class.
     * <p>
     * This {@link Configuration} is used to load and initialize modules, see
     * {@link #loadJarvisModuleFromModuleModel(Module)} for more information on module loading.
     *
     * @see #loadJarvisModuleFromModuleModel(Module)
     */
    private Configuration configuration;

    /**
     * The {@link DialogFlowApi} used to access the DialogFlow framework and send user input for
     * {@link RecognizedIntent}
     * extraction.
     */
    private DialogFlowApi dialogFlowApi;

    /**
     * The {@link JarvisModuleRegistry} used to cache loaded module, and provides utility method to retrieve,
     * unregister,
     * and clear them.
     *
     * @see #getJarvisModuleRegistry()
     */
    private JarvisModuleRegistry jarvisModuleRegistry;

    /**
     * The {@link IntentDefinitionRegistry} used to cache {@link fr.zelus.jarvis.intent.IntentDefinition} from the input
     * {@link OrchestrationModel} and provides utility methods to retrieve specific
     * {@link fr.zelus.jarvis.intent.IntentDefinition} and clear the cache.
     *
     * @see #getIntentDefinitionRegistry()
     */
    private IntentDefinitionRegistry intentDefinitionRegistry;

    /**
     * The {@link OrchestrationService} used to find {@link JarvisAction}s to execute from the received textual inputs.
     *
     * @see #handleMessage(String, JarvisSession)
     * @see JarvisAction
     */
    private OrchestrationService orchestrationService;

    /**
     * The {@link ExecutorService} used to process {@link JarvisAction}s returned by the registered
     * {@link JarvisModule}s.
     *
     * @see JarvisModule
     * @see JarvisAction
     */
    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    /**
     * The {@link Map} used to store and retrieve {@link JarvisSession}s associated to users.
     *
     * @see #getOrCreateJarvisSession(String)
     */
    private Map<String, JarvisSession> sessions;

    /**
     * Constructs a new {@link JarvisCore} instance from the provided {@code configuration}.
     * <p>
     * The provided {@code configuration} must provide values for the following keys:
     * <ul>
     * <li><b>dialogflow.projectId</b>: the unique identifier of the DialogFlow project</li>
     * <li><b>dialogflow.language</b>: the code of the language processed by DialogFlow</li>
     * <li><b>jarvis.orchestration.model</b>: the {@link OrchestrationModel} defining the Intent to
     * Action bindings (or the string representing its location)</li>
     * <li><b>jarvis.input.provider</b>: the {@link InputProvider} to receive input from</li>
     * </ul>
     * <p>
     * The provided {@link OrchestrationModel} defines the Intent to Action bindings that are executed by the
     * application. This constructor takes care of loading the {@link JarvisModule}s associated to the provided
     * {@code orchestrationModel} and enables the corresponding {@link JarvisAction}s.
     * <p>
     * The provided {@link InputProvider} is run in a dedicated {@link Thread} and uses this class to provide user
     * inputs.
     * <p>
     * <b>Note:</b> the {@link JarvisModule}s associated to the provided {@code orchestrationModel} have to be
     * in the classpath in order to be dynamically loaded and instantiated.
     *
     * @param configuration the {@link Configuration} to construct the instance from
     * @throws NullPointerException if the provided {@code configuration} or one of the mandatory values is {@code null}
     * @throws JarvisException      if the framework is not able to retrieve the {@link OrchestrationModel}
     * @see OrchestrationModel
     */
    public JarvisCore(Configuration configuration) {
        checkNotNull(configuration, "Cannot construct a jarvis instance from a null configuration");
        this.configuration = configuration;
        String projectId = configuration.getString(PROJECT_ID_KEY);
        checkNotNull(projectId, "Cannot construct a jarvis instance from a null projectId");
        String languageCode = configuration.getString(LANGUAGE_CODE_KEY);
        checkNotNull(languageCode, "Cannot construct a jarvis instance from a null language code");
        OrchestrationModel orchestrationModel = getOrchestrationModel(configuration.getProperty
                (ORCHESTRATION_MODEL_KEY));
        checkNotNull(orchestrationModel, "Cannot construct a jarvis instance from a null orchestration model");
        this.dialogFlowApi = new DialogFlowApi(this, projectId, languageCode);
        this.sessions = new HashMap<>();
        /*
         * The OrchestrationService instance should be available through a getter for testing purposes.
         * See https://github.com/gdaniel/jarvis/issues/6.
         */
        this.orchestrationService = new OrchestrationService(orchestrationModel);
        this.jarvisModuleRegistry = new JarvisModuleRegistry();
        this.intentDefinitionRegistry = new IntentDefinitionRegistry();
        boolean intentRegistered = false;
        for (InputProviderDefinition inputProviderDefinition : orchestrationModel.getInputProviderDefinitions()) {
            Module inputProviderModule = (Module) inputProviderDefinition.eContainer();
            JarvisModule inputProviderJarvisModule = this.jarvisModuleRegistry.getJarvisModule(inputProviderModule
                    .getName());
            if (isNull(inputProviderJarvisModule)) {
                inputProviderJarvisModule = loadJarvisModuleFromModuleModel(inputProviderModule);
                this.jarvisModuleRegistry.registerJarvisModule(inputProviderJarvisModule);
            }
            inputProviderJarvisModule.startInputProvider(inputProviderDefinition, this);
        }
        for (OrchestrationLink link : orchestrationModel.getOrchestrationLinks()) {
            /*
             * Extracts the IntentDefinitions
             */
            this.intentDefinitionRegistry.registerIntentDefinition(link.getIntent());
            try {
                this.dialogFlowApi.registerIntentDefinition(link.getIntent());
                intentRegistered = true;
            } catch (DialogFlowException e) {
                Log.warn("The Intent {0} is already registered in the DialogFlow project, skipping its registration",
                        link.getIntent().getName());
                Log.warn("Intent {0} won't be updated on the DialogFlow project", link.getIntent().getName());
            }
            /*
             * Load the action modules
             */
            for (ActionInstance actionInstance : link.getActions()) {
                Action action = actionInstance.getAction();
                Module module = (Module) action.eContainer();
                JarvisModule jarvisModule = this.jarvisModuleRegistry.getJarvisModule(module.getName());
                if (isNull(jarvisModule)) {
                    jarvisModule = loadJarvisModuleFromModuleModel(module);
                    this.jarvisModuleRegistry.registerJarvisModule(jarvisModule);
                }
                jarvisModule.enableAction(action);
            }
        }
        if (intentRegistered) {
            /*
             * New intents have been registered in the DialogFlow project, we should explicitly ask the ML Engine
             * to train in order to take them into account
             */
            dialogFlowApi.trainMLEngine();
        }
        Log.info("Jarvis bot started");
    }

    /**
     * Constructs a new {@link JarvisCore} instance with the provided {@code projectId}, {@code languageCode}, and
     * {@code orchestrationModel}.
     * <p>
     * The provided {@link OrchestrationModel} defines the Intent to Action bindings that are executed by the
     * application. This constructor takes care of loading the {@link JarvisModule}s associated to the provided
     * {@code orchestrationModel} and enables the corresponding {@link JarvisAction}s.
     * <p>
     * <b>Note:</b> the {@link JarvisModule}s associated to the provided {@code orchestrationModel} have to be in the
     * classpath in order to be dynamically loaded and instantiated.
     *
     * @param projectId          the unique identifier of the DialogFlow project
     * @param languageCode       the code of the language processed by DialogFlow
     * @param orchestrationModel the {@link OrchestrationModel} defining the Intent to Action bindings
     * @throws NullPointerException if the provided {@code projectId}, {@code languageCode}, {@code
     *                              orchestrationModel}, or {@code inputProvider} is {@code null}
     * @throws JarvisException      if the framework is not able to retrieve the {@link OrchestrationModel}
     * @see #JarvisCore(Configuration)
     * @see OrchestrationModel
     * @see InputProvider
     */
    public JarvisCore(String projectId, String languageCode, OrchestrationModel orchestrationModel) {
        this(buildConfiguration(projectId, languageCode, orchestrationModel));
    }

    /**
     * Retrieves the {@link OrchestrationModel} from the provided {@code property}.
     * <p>
     * This method checks if the provided {@code property} is already an in-memory {@link OrchestrationModel}
     * instance, or if it is defined by a {@link String} or an {@link URI} representing the path of the model. In
     * that case, the method attempts to load the model at the provided location and returns it.
     * <p>
     * This method supports loading of model path defined by {@link String}s and {@link URI}s. Support for additional
     * types is planned in the next releases.
     *
     * @param property the {@link Object} representing the {@link OrchestrationModel} to extract
     * @return the {@link OrchestrationModel} from the provided {@code property}
     * @throws JarvisException      if the provided {@code property} type is not handled, if the
     *                              underlying {@link Resource} cannot be loaded or if it does not contain an
     *                              {@link OrchestrationModel} top-level
     *                              element, or if the loaded {@link OrchestrationModel} is empty.
     * @throws NullPointerException if the provided {@code property} is {@code null}
     */
    protected OrchestrationModel getOrchestrationModel(Object property) {
        checkNotNull(property, "Cannot retrieve the OrchestrationModel from the property null, please ensure it is " +
                "set in the %s property of the jarvis configuration", ORCHESTRATION_MODEL_KEY);
        /*
         * Register the EPackages used in the OrchestrationModel
         */
        EPackage.Registry.INSTANCE.put(OrchestrationPackage.eINSTANCE.getNsURI(), OrchestrationPackage.eINSTANCE);
        EPackage.Registry.INSTANCE.put(IntentPackage.eINSTANCE.getNsURI(), IntentPackage.eINSTANCE);
        EPackage.Registry.INSTANCE.put(ModulePackage.eINSTANCE.getNsURI(), ModulePackage.eINSTANCE);
        if (property instanceof OrchestrationModel) {
            return (OrchestrationModel) property;
        } else {
            URI uri;
            if (property instanceof String) {
                uri = URI.createURI((String) property);
            } else if (property instanceof URI) {
                uri = (URI) property;
            } else {
                // Unknown property type
                String errorMessage = MessageFormat.format("Cannot retrieve the OrchestrationModel from the provided " +
                        "property {0}, the property type ({1}) is not supported", property, property.getClass()
                        .getSimpleName());
                Log.error(errorMessage);
                throw new JarvisException(errorMessage);
            }
            ResourceSet resourceSet = new ResourceSetImpl();
            resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl
                    ());
            Resource orchestrationModelResource;
            try {
                orchestrationModelResource = resourceSet.getResource(uri, true);
            } catch (Exception e) {
                throw new JarvisException(MessageFormat.format("Cannot load the OrchestrationModel at the given " +
                        "location: {0}", uri.toString()), e);
            }
            if (isNull(orchestrationModelResource)) {
                String errorMessage = MessageFormat.format("Cannot load the provided orchestration model (uri: {0})",
                        uri);
                Log.error(errorMessage);
                throw new JarvisException(errorMessage);
            }
            if (orchestrationModelResource.getContents().isEmpty()) {
                String errorMessage = MessageFormat.format("The provided orchestration model is empty (uri: {0})",
                        orchestrationModelResource.getURI());
                Log.error(errorMessage);
                throw new JarvisException(errorMessage);
            }
            OrchestrationModel orchestrationModel = null;
            try {
                orchestrationModel = (OrchestrationModel) orchestrationModelResource.getContents().get(0);
            } catch (ClassCastException e) {
                String errorMessage = MessageFormat.format("The provided orchestration model does not contain a " +
                        "top-level" +
                        " element with the type OrchestrationModel (uri: {0})", orchestrationModelResource.getURI());
                Log.error(errorMessage);
                throw new JarvisException(errorMessage, e);
            }
            return orchestrationModel;
        }
    }

    /**
     * Loads the {@link JarvisModule} defined by the provided {@link Module} definition.
     * <p>
     * This method searches in the classpath a {@link Class} matching the input {@link Module#getJarvisModulePath()}
     * value and calls its default constructor.
     *
     * @param moduleModel the jarvis {@link Module} definition to load
     * @return an instance of the loaded {@link JarvisModule}
     * @throws JarvisException if their is no {@link Class} matching the provided {@code moduleModel} or if the
     *                         {@link JarvisModule} can not be constructed
     * @see Module
     */
    private JarvisModule loadJarvisModuleFromModuleModel(Module moduleModel) throws JarvisException {
        Log.info("Loading JarvisModule {0}", moduleModel.getName());
        Class<? extends JarvisModule> jarvisModuleClass = Loader.loadClass(moduleModel.getJarvisModulePath(),
                JarvisModule.class);
        try {
            return Loader.construct(jarvisModuleClass, Configuration.class, this.configuration);
        } catch (NoSuchMethodException e) {
            Log.warn("Cannot find the method {0}({1}), trying to initialize the module using its default " +
                    "constructor", jarvisModuleClass.getSimpleName(), Configuration.class.getSimpleName());
            JarvisModule jarvisModule = Loader.construct(jarvisModuleClass);
            Log.warn("Module {0} loaded with its default constructor, the module will not be initialized with " +
                    "jarvis configuration", jarvisModuleClass.getSimpleName());
            return jarvisModule;
        }
    }

    /**
     * Returns the {@link DialogFlowApi} used to query the DialogFlow framework.
     * <p>
     * <b>Note:</b> this method is designed to ease debugging and testing, direct interactions with the DialogFlow
     * API may create consistency issues. In particular, jarvis does not ensure that {@link JarvisAction}s will be
     * triggered in case of direct queries to the DialogFlow API.
     *
     * @return the {@link DialogFlowApi} used to query the DialogFlow framework
     */
    public DialogFlowApi getDialogFlowApi() {
        return dialogFlowApi;
    }

    /**
     * Returns the {@link IntentDefinitionRegistry} associated to this instance.
     * <p>
     * This registry is used to cache {@link fr.zelus.jarvis.intent.IntentDefinition} from the input
     * {@link OrchestrationModel} and provides utility methods to retrieve specific
     * {@link fr.zelus.jarvis.intent.IntentDefinition} and clear the cache.
     *
     * @return the {@link IntentDefinitionRegistry} associated to this instance
     */
    public IntentDefinitionRegistry getIntentDefinitionRegistry() {
        return intentDefinitionRegistry;
    }

    /**
     * Returns the {@link JarvisModuleRegistry} associated to this instance.
     * <p>
     * This registry is used to cache loaded module, and provides utility method to retrieve, unregister, and clear
     * them.
     *
     * @return the {@link JarvisModuleRegistry} associated to this instance
     */
    public JarvisModuleRegistry getJarvisModuleRegistry() {
        return jarvisModuleRegistry;
    }

    /**
     * Returns the {@link OrchestrationModel} associated to this class' {@link OrchestrationService}.
     * <p>
     * This method eases the access to the underlying {@link OrchestrationModel} for client applications.
     *
     * @return the {@link OrchestrationModel} associated to this class' {@link OrchestrationService}
     * @see OrchestrationService#getOrchestrationModel()
     */
    public OrchestrationModel getOrchestrationModel() {
        return orchestrationService.getOrchestrationModel();
    }

    /**
     * Returns the {@link ExecutorService} used to process {@link JarvisAction}s.
     * <p>
     * <b>Note:</b> this method is designed to ease testing, and should not be accessed by client applications.
     * Manipulating {@link JarvisCore}'s {@link ExecutorService} may create consistency issues on currently executed
     * {@link JarvisAction}s.
     *
     * @return the {@link ExecutorService} used to process {@link JarvisAction}s
     */
    protected ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Shuts down the {@link JarvisCore} and the underlying engines.
     * <p>
     * This method shuts down the underlying {@link DialogFlowApi}, unloads all the {@link JarvisModule}s associated to
     * this instance, unregisters the {@link fr.zelus.jarvis.intent.IntentDefinition} from the associated
     * {@link IntentDefinitionRegistry}, and shuts down the {@link #executorService}.
     * <p>
     * <b>Note:</b> calling this method invalidates the DialogFlow connection, and thus shuts down intent detections
     * and voice recognitions features. New {@link JarvisAction}s cannot be processed either.
     *
     * @see DialogFlowApi#shutdown()
     */
    public void shutdown() {
        Log.info("Shutting down JarvisCore");
        if (isShutdown()) {
            throw new JarvisException("Cannot perform shutdown, JarvisCore is already shutdown");
        }
        // Shutdown the executor first in case there are running tasks using the DialogFlow API.
        this.executorService.shutdownNow();
        this.dialogFlowApi.shutdown();
        this.getJarvisModuleRegistry().clearJarvisModules();
        this.getIntentDefinitionRegistry().clearIntentDefinitions();
    }

    /**
     * Returns whether the {@link JarvisCore} client is shutdown.
     * <p>
     * This class is considered as shutdown if either its underlying {@link ExecutorService} or {@link DialogFlowApi}
     * is shutdown, or if its {@link SessionName} is {@code null}.
     *
     * @return {@code true} if the {@link JarvisCore} client is shutdown, {@code false} otherwise
     */
    public boolean isShutdown() {
        return executorService.isShutdown() || dialogFlowApi.isShutdown();
    }

    /**
     * Retrieves or creates the {@link JarvisSession} associated to the provided {@code sessionId}.
     * <p>
     * If the {@link JarvisSession} does not exist a new one is created using
     * {@link DialogFlowApi#createSession(String)}.
     *
     * @param sessionId the identifier to get or retrieve a session from
     * @return the {@link JarvisSession} associated to the provided {@code sessionId}
     * @throws NullPointerException if the provided {@code sessionId} is {@code null}
     */
    public JarvisSession getOrCreateJarvisSession(String sessionId) {
        checkNotNull(sessionId, "Cannot create or retrieve a session from null as the session ID");
        JarvisSession session = getJarvisSession(sessionId);
        if (isNull(session)) {
            session = this.dialogFlowApi.createSession(sessionId);
            sessions.put(sessionId, session);
        }
        return session;
    }

    /**
     * Returns the {@link JarvisSession} associated to the provided {@code sessionId}
     *
     * @param sessionId the identifier to retrieve the session from
     * @return the {@link JarvisSession} associated to the provided {@code sessionId}
     * @throws NullPointerException if the provided {@code sessionId} is {@code null}
     */
    public JarvisSession getJarvisSession(String sessionId) {
        checkNotNull(sessionId, "Cannot retrieve a session from null as the session ID");
        return sessions.get(sessionId);
    }

    /**
     * Invalidates all the {@link JarvisSession}s and clear the session registry.
     */
    public void clearJarvisSessions() {
        this.sessions.clear();
    }

    /**
     * Handles an input {@code message} by executing the associated {@link JarvisAction}s.
     * <p>
     * The input {@code message} is forwarded to the underlying {@link DialogFlowApi} that takes care of retrieving
     * the corresponding intent (if any).
     * <p>
     * The message is handled for a specific user, and can access the {@code session} information to retrieve stored
     * context variables, extracted information, are additional information stored by the {@link InputProvider}.
     * <p>
     * This method relies on the {@link OrchestrationService} instance to retrieve the {@link JarvisAction}
     * associated to the extracted intent. These {@link JarvisAction}s are then submitted to the local
     * {@link #executorService} that takes care of executing them in a separated thread.
     *
     * @param message the textual input to process
     * @param session the {@link JarvisSession} associated to the input user
     * @throws NullPointerException if the provided {@code message} is {@code null}
     * @see JarvisAction
     */
    public void handleMessage(String message, JarvisSession session) {
        checkNotNull(message, "Cannot handle null message");
        checkNotNull(session, "Cannot handle the message %s, the provided session is null", message);
        RecognizedIntent intent = dialogFlowApi.getIntent(message, session);
        /*
         * Register the returned context values
         */
        for (ContextParameterValue contextParameterValue : intent.getOutContextValues()) {
            String contextName = ((Context) contextParameterValue.getContextParameter().eContainer()).getName();
            String parameterName = contextParameterValue.getContextParameter().getName();
            String parameterValue = contextParameterValue.getValue();
            session.getJarvisContext().setContextValue(contextName, parameterName, parameterValue);
        }
        List<ActionInstance> actionInstances = orchestrationService.getActionsFromIntent(intent);
        if (actionInstances.isEmpty()) {
            Log.warn("The intent {0} is not associated to any action", intent.getDefinition().getName());
        }
        for (ActionInstance actionInstance : actionInstances) {
            JarvisModule jarvisModule = this.getJarvisModuleRegistry().getJarvisModule((Module) actionInstance
                    .getAction().eContainer());
            JarvisAction action = jarvisModule.createJarvisAction(actionInstance, intent, session);
            Future<Object> result = executorService.submit(action);
            if (nonNull(action.getReturnVariable())) {
                /*
                 * Store the Future, so we can run concurrently JarvisActions that are not related.
                 */
                Log.info("Registering context variable {0} with value {1}", action.getReturnVariable(), result);
                session.getJarvisContext().setContextValue("variables", action.getReturnVariable(), result);
            }
        }
    }
}
