package com.google.copybara.config.skylark;

import static com.google.common.base.Preconditions.checkState;
import static com.google.copybara.config.ConfigValidationException.checkCondition;
import static com.google.copybara.config.ConfigValidationException.checkNotMissing;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.copybara.Authoring;
import com.google.copybara.Core;
import com.google.copybara.EnvironmentException;
import com.google.copybara.GeneralOptions;
import com.google.copybara.Options;
import com.google.copybara.Workflow;
import com.google.copybara.WorkflowOptions;
import com.google.copybara.config.Config;
import com.google.copybara.config.ConfigValidationException;
import com.google.copybara.util.console.Console;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Environment.Frame;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor;
import com.google.devtools.build.lib.syntax.ValidationEnvironment;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An experiment that loads Copybara configs from bzl files.
 */
public class SkylarkParser {

  private static final Logger logger = Logger.getLogger(SkylarkParser.class.getName());
  // For now all the modules are namespaces. We don't use variables except for 'core'.
  private final Iterable<Class<?>> modules;

  public SkylarkParser(Set<Class<?>> modules) {
    this.modules = ImmutableSet.<Class<?>>builder()
        .add(Authoring.Module.class)
        .add(Core.class)
        .addAll(modules).build();

    // Register module functions
    for (Class<?> module : this.modules) {
      logger.log(Level.INFO, "Registering module " + module.getName());
      // This method should be only called once for VM or at least not concurrently,
      // since it registers functions in an static HashMap.
      SkylarkSignatureProcessor.configureSkylarkFunctions(module);
    }
  }

  public Config loadConfig(String content, Options options)
      throws IOException, ConfigValidationException, EnvironmentException {
    Core core;
    try {
      Environment env = executeSkylark(content, options);

      core = (Core) env.getGlobals().get(Core.CORE_VAR);
    } catch (InterruptedException e) {
      // This should not happen since we shouldn't have anything interruptable during loading.
      throw new RuntimeException("Internal error", e);
    }
    return createConfig(options, core.getWorkflows(), core.getProjectName());
  }

  @VisibleForTesting
  public Environment executeSkylark(String content, Options options)
      throws IOException, ConfigValidationException, InterruptedException {
    Console console = options.get(GeneralOptions.class).console();
    EventHandler eventHandler = new ConsoleEventHandler(console);

    Frame globals = createGlobals(eventHandler, options);
    Environment env = createEnvironment(eventHandler, globals);

    BuildFileAST buildFileAST = parseFile(content, eventHandler, env);
    // TODO(malcon): multifile support
    checkState(buildFileAST.getImports().isEmpty(),
        "load() statements are still not supported: %s", buildFileAST.getImports());

    checkCondition(buildFileAST.exec(env, eventHandler), "Error loading config file");
    return env;
  }

  private Config createConfig(Options options, Map<String, Workflow<?>> workflows,
      String projectName)
      throws ConfigValidationException {

    checkCondition(!workflows.isEmpty(), "At least one workflow is required.");

    String workflowName = options.get(WorkflowOptions.class).getWorkflowName();
    Workflow<?> workflow = workflows.get(workflowName);
    checkCondition(workflow != null, String.format(
        "No workflow with '%s' name exists. Valid workflows: %s",
        workflowName, workflows.keySet()));
    return new Config(checkNotMissing(projectName, "project"), workflow);
  }

  private BuildFileAST parseFile(String content, EventHandler eventHandler, Environment env)
      throws IOException {
    ValidationEnvironment validationEnvironment = new ValidationEnvironment(env);
    InMemoryFileSystem fs = new InMemoryFileSystem();
    // TODO(malcon): Use real file name
    com.google.devtools.build.lib.vfs.Path config = fs.getPath("/config.bzl");
    FileSystemUtils.writeIsoLatin1(config, content);

    return BuildFileAST.parseSkylarkFile(config, eventHandler, validationEnvironment);
  }

  /**
   * Creates a Skylark environment making the {@code modules} available as global variables.
   *
   * <p>For the modules that implement {@link OptionsAwareModule}, options are set in the object so that
   * the module can construct objects that require options.
   */
  private Environment createEnvironment(EventHandler eventHandler, Environment.Frame globals) {
    return Environment.builder(Mutability.create("CopybaraModules"))
        .setGlobals(globals)
        .setSkylark()
        .setEventHandler(eventHandler)
        .build();
  }

  /**
   * Create native global variables from the modules
   *
   * <p>The returned object can be reused for different instances of environments.
   */
  private Environment.Frame createGlobals(EventHandler eventHandler,
      Options options) {
    Environment env = createEnvironment(eventHandler, Environment.SKYLARK);

    for (Class<?> module : modules) {
      logger.log(Level.INFO, "Creating variable for " + module.getName());
      // Create the module object and associate it with the functions
      Runtime.registerModuleGlobals(env, module);
      // Add the options to the module that require them
      if (OptionsAwareModule.class.isAssignableFrom(module)) {
        ((OptionsAwareModule) getModuleGlobal(env, module)).setOptions(options);
      }
    }
    env.mutability().close();
    return env.getGlobals();
  }

  /**
   * Given an environment, find the corresponding global object representing the module.
   */
  private Object getModuleGlobal(Environment env, Class<?> module) {
    return env.getGlobals().get(module.getAnnotation(SkylarkModule.class).name());
  }

  /**
   * An EventHandler that does the translation to {@link Console} events.
   */
  private static class ConsoleEventHandler implements EventHandler {

    private final Console console;

    private ConsoleEventHandler(Console console) {
      this.console = console;
    }

    @Override
    public void handle(Event event) {
      switch (event.getKind()) {

        case ERROR:
          console.error(messageWithLocation(event));
          break;
        case WARNING:
          console.warn(messageWithLocation(event));
          break;
        case INFO:
          console.info(messageWithLocation(event));
          break;
        case PROGRESS:
          console.progress(messageWithLocation(event));
          break;
        case STDOUT:
          System.out.println(event);
          break;
        case STDERR:
          System.err.println(event);
          break;
        default:
          System.err.println("Unknown message type: " + event);
      }
    }

    private String messageWithLocation(Event event) {
      String location = event.getLocation() == null
          ? "<no location>"
          : event.getLocation().print();
      return location + ": " + event.getMessage();
    }
  }
}