package fr.zelus.jarvis.core.module.log.action;

import fr.zelus.jarvis.core.session.JarvisContext;

public class LogWarningTest extends LogActionTest {

    private static String WARNING_TAG = "[WARN]";

    @Override
    protected LogAction createLogAction(String message) {
        return new LogWarning(logModule, new JarvisContext(), message);
    }

    @Override
    protected String expectedLogTag() {
        return WARNING_TAG;
    }
}