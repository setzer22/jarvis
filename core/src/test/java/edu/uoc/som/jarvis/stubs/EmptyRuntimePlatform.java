package edu.uoc.som.jarvis.stubs;

import edu.uoc.som.jarvis.core.JarvisCore;
import edu.uoc.som.jarvis.core.platform.RuntimePlatform;
import edu.uoc.som.jarvis.core.session.JarvisSession;
import edu.uoc.som.jarvis.execution.ActionInstance;

/**
 * An empty {@link RuntimePlatform} used to test {@link RuntimePlatform}-related methods.
 * <p>
 * See {@link StubRuntimePlatform} to create a stub {@link RuntimePlatform} that provided preset
 * {@link StubRuntimePlatform#getAction()} and
 * {@link StubRuntimePlatform#createRuntimeAction(ActionInstance, JarvisSession)} methods.
 */
public class EmptyRuntimePlatform extends RuntimePlatform {

    /**
     * Constructs a new {@link EmptyRuntimePlatform} from the provided {@link JarvisCore}.
     *
     * @param jarvisCore the {@link JarvisCore} instance associated to this runtimePlatform
     * @throws NullPointerException if the provided {@code jarvisCore} is {@code null}
     */
    public EmptyRuntimePlatform(JarvisCore jarvisCore) {
        super(jarvisCore);
    }
}
