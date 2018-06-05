package fr.zelus.jarvis.core;

import com.google.cloud.dialogflow.v2.Intent;
import com.google.cloud.dialogflow.v2.SessionName;
import fr.inria.atlanmod.commons.log.Log;
import fr.zelus.jarvis.dialogflow.DialogFlowApi;
import org.apache.commons.configuration2.Configuration;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

import static fr.inria.atlanmod.commons.Preconditions.checkNotNull;

/**
 * A message broker that receives input messages, retrieve their {@link Intent}s, and dispatch them to the registered
 * {@link JarvisModule}s.
 *
 * @see JarvisModule
 */
public class JarvisCore {

    /**
     * The {@link DialogFlowApi} used to access the DialogFlow framework and send user input for {@link Intent}
     * extraction.
     */
    private DialogFlowApi dialogFlowApi;

    /**
     * The DialogFlow session associated to this {@link JarvisCore} instance.
     */
    private SessionName sessionName;

    /**
     * The {@link JarvisModule}s used to handle {@link Intent}s extracted from user input.
     *
     * @see #handleMessage(String)
     */
    private List<JarvisModule> modules;

    /**
     * Constructs a new {@link JarvisCore} with the provided {@code projectId} and {@code languageCode}.
     * <p>
     * Note that this constructor initializes an empty {@link #modules} list. Use
     * {@link #registerModule(JarvisModule)} to register additional {@link JarvisModule}. See
     * {@link #JarvisCore(String, String, List)} to construct a {@link JarvisCore} instance with preset
     * {@link JarvisModule}s.
     *
     * @param projectId    the unique identifier of the DialogFlow project
     * @param languageCode the code of the language processed by DialogFlow
     * @see #registerModule(JarvisModule)
     * @see #unregisterModule(JarvisModule)
     */
    public JarvisCore(String projectId, String languageCode) {
        this(projectId, languageCode, new ArrayList<>());
    }

    // Needs to be tested
    public JarvisCore(Configuration configuration) {
        this(configuration.getString("projectId"), configuration.getString("languageCode"), configuration.getList
                (JarvisModule.class, "modules"));
    }

    /**
     * Constructs a new {@link JarvisCore} with the provided {@code projectId}, {@code languageCode}, and {@code
     * modules}.
     *
     * @param projectId    the unique identifier of the DialogFlow project
     * @param languageCode the code of the language processed by DialogFlow
     * @param modules      the {@link JarvisModule}s used to handle {@link Intent}s extracted from user input.
     * @throws NullPointerException if the provided {@code projectId}, {@code languageCode}, or {@code modules} is
     *                              {@code null}
     * @see #handleMessage(String)
     */
    public JarvisCore(String projectId, String languageCode, List<JarvisModule> modules) {
        checkNotNull(projectId, "Cannot construct a jarvis instance from a null projectId");
        checkNotNull(languageCode, "Cannot construct a jarvis instance from a null language code");
        checkNotNull(modules, "Cannot construct a jarvis instance from a null module list");
        this.dialogFlowApi = new DialogFlowApi(projectId, languageCode);
        this.sessionName = dialogFlowApi.createSession();
        this.modules = modules;
    }

    /**
     * Registers a new {@link JarvisModule} to the {@link #modules} list.
     *
     * @param module the {@link JarvisModule} to register
     * @throws NullPointerException if the provided {@code module} is {@code null}
     */
    public void registerModule(JarvisModule module) {
        checkNotNull(module, "Cannot register the module null");
        this.modules.add(module);
    }

    /**
     * Unregisters a {@link JarvisModule} from the {@link #modules} list.
     *
     * @param module the {@link JarvisModule} to unregister
     * @throws NullPointerException     if the provided {@code module} is {@code null}
     * @throws IllegalArgumentException if the provided {@code module} hasn't been removed from the list
     */
    public void unregisterModule(JarvisModule module) {
        checkNotNull(module, "Cannot unregister the module null");
        boolean removed = this.modules.remove(module);
        if (!removed) {
            throw new IllegalArgumentException(MessageFormat.format("Cannot remove {0} from the module list, please " +
                    "ensure that this module is in the list", module));
        }
    }

    /**
     * Unregisters all the {@link JarvisModule}s.
     */
    public void clearModules() {
        this.modules.clear();
    }

    /**
     * Handles a new input message and dispatch it through the registered {@code modules}.
     * <p>
     * This method relies on the {@link DialogFlowApi} to retrieve the {@link Intent} of the input message, and
     * notifies all the registered modules of the new {@link Intent}.
     *
     * @param message the input message
     * @see JarvisModule#handleIntent(Intent)
     * @see JarvisModule#acceptIntent(Intent)
     */
    public void handleMessage(String message) {
        Intent intent = dialogFlowApi.getIntent(message, sessionName);
        boolean handled = false;
        for (JarvisModule module : modules) {
            if (module.acceptIntent(intent)) {
                module.handleIntent(intent);
                /*
                 * There is at least one module that can handle the intent
                 */
                handled = true;
            }
        }
        if (!handled) {
            /*
             * Log an error if the intent hasn't been handled. Note that not handling an intent is not a text
             * recognition issue on the DialogFlow side (the framework was able to detect an intent), but a jarvis
             * issue: there is no registered module that can handle the intent returned by DialogFlow.
             */
            Log.warn("The intent {0} hasn't been handled, make sure that the corresponding JarvisModule is loaded " +
                    "and registered", intent.getDisplayName());
        }
    }
}
