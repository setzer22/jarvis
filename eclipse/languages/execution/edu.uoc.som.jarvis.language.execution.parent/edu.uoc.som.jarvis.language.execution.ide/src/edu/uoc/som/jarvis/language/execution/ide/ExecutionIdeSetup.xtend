/*
 * generated by Xtext 2.12.0
 */
package edu.uoc.som.jarvis.language.execution.ide

import com.google.inject.Guice
import org.eclipse.xtext.util.Modules2
import edu.uoc.som.jarvis.language.execution.ExecutionStandaloneSetup
import edu.uoc.som.jarvis.language.execution.ExecutionRuntimeModule

/**
 * Initialization support for running Xtext languages as language servers.
 */
class ExecutionIdeSetup extends ExecutionStandaloneSetup {

	override createInjector() {
		Guice.createInjector(Modules2.mixin(new ExecutionRuntimeModule, new ExecutionIdeModule))
	}
	
}
