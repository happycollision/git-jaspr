package sims.michael.gitjaspr

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Level.*
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.sources.ChainedValueSource
import com.github.ajalt.clikt.sources.PropertiesValueSource
import com.github.ajalt.clikt.sources.ValueSource.Companion.getKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import java.io.File

//region Commands
class Status : GitJasprCommand(help = "Show status of current stack") {
    private val refSpec by argument()
        .convert(conversion = ArgumentTransformContext::convertRefSpecString)
        .help {
            """
            A refspec in the form `[[local-object:]target-ref]`. Patterned after a typical git refspec, it describes a 
            local commit, followed by a colon, followed by the name of a target branch on the remote. The local commit
            is compared to the target ref to determine which commits should be included in the status report.
            The local object name (and the colon) can be omitted, in which case the default is 
            `$DEFAULT_LOCAL_OBJECT`. If the target-ref is also omitted, it defaults to the value of the 
            `${defaultTargetRefDelegate.names.single()}` option or `$DEFAULT_TARGET_REF`.
            """.trimIndent()
        }
        .defaultLazy { RefSpec(DEFAULT_LOCAL_OBJECT, defaultTargetRef) }

    override suspend fun doRun() {
        print(appWiring.gitJaspr.getStatusString(refSpec))
    }
}

class Push : GitJasprCommand(help = "Push local commits to the remote and open PRs for each one") {
    private val refSpec by argument()
        .convert(conversion = ArgumentTransformContext::convertRefSpecString)
        .help {
            """
            A refspec in the form `[[local-object:]target-ref]`. Patterned after a typical git refspec, it describes the 
            local commit that should be pushed to the remote, followed by a colon, followed by the name of the target 
            branch on the remote. The local object name (and the colon) can be omitted, in which case the default is 
            `$DEFAULT_LOCAL_OBJECT`. If the target-ref is also omitted, it defaults to the value of the 
            `${defaultTargetRefDelegate.names.single()}` option or `$DEFAULT_TARGET_REF`.
            """.trimIndent()
        }
        .defaultLazy { RefSpec(DEFAULT_LOCAL_OBJECT, defaultTargetRef) }

    override suspend fun doRun() = appWiring.gitJaspr.push(refSpec)
}

class Merge : GitJasprCommand(help = "Merge all local commits that are mergeable") {
    private val refSpec by argument()
        .convert(conversion = ArgumentTransformContext::convertRefSpecString)
        .help {
            """
            A refspec in the form `[[local-object:]target-ref]`. Patterned after a typical git refspec, it describes a 
            local commit, followed by a colon, followed by the name of a target branch on the remote. The local commit
            is compared to the target ref to determine which commits should be included in the merge.
            The local object name (and the colon) can be omitted, in which case the default is 
            `$DEFAULT_LOCAL_OBJECT`. If the target-ref is also omitted, it defaults to the value of the 
            `${defaultTargetRefDelegate.names.single()}` option or `$DEFAULT_TARGET_REF`.
            """.trimIndent()
        }
        .defaultLazy { RefSpec(DEFAULT_LOCAL_OBJECT, defaultTargetRef) }

    override suspend fun doRun() = appWiring.gitJaspr.merge(refSpec)
}

class AutoMerge : GitJasprCommand(help = "Poll GitHub until all local commits are mergeable, then merge them") {
    private val refSpec by argument()
        .convert(conversion = ArgumentTransformContext::convertRefSpecString)
        .help {
            """
            A refspec in the form `[[local-object:]target-ref]`. Patterned after a typical git refspec, it describes a 
            local commit, followed by a colon, followed by the name of a target branch on the remote. The local commit
            is compared to the target ref to determine which commits should be included in the merge.
            The local object name (and the colon) can be omitted, in which case the default is 
            `$DEFAULT_LOCAL_OBJECT`. If the target-ref is also omitted, it defaults to the value of the 
            `${defaultTargetRefDelegate.names.single()}` option or `$DEFAULT_TARGET_REF`.
            """.trimIndent()
        }
        .defaultLazy { RefSpec(DEFAULT_LOCAL_OBJECT, defaultTargetRef) }

    private val interval by option("--interval", "-i")
        .int()
        .default(10)
        .help { "Polling interval in seconds. Setting this too low may exhaust GitHub rate limiting" }

    override suspend fun doRun() = appWiring.gitJaspr.autoMerge(refSpec, interval)
}

class Clean : GitJasprCommand(help = "Clean up orphaned jaspr branches") {
    private val forceDelegate = option("-f").flag("--no-force", default = false).help {
        "Supply this flag to remove orphaned branches"
    }
    private val force by forceDelegate
    override suspend fun doRun() {
        if (!force) {
            Cli.logger.info("Refusing to delete branches without the ${forceDelegate.names.first()} option.")
        }
        appWiring.gitJaspr.clean(dryRun = !force)
    }
}

class InstallCommitIdHook : GitJasprCommand(help = "Install commit-msg hook that adds commit-id's to local commits") {
    override suspend fun doRun() {
        appWiring.gitJaspr.installCommitIdHook()
    }
}

// Used by tests
class NoOp : GitJasprCommand(help = "Do nothing", hidden = true) {
    private val logger = LoggerFactory.getLogger(NoOp::class.java)

    @Suppress("unused")
    val extraArgs by argument().multiple() // Ignore extra args

    override suspend fun doRun() {
        logger.info(commandName)
    }
}
//endregion

private class GitHubOptions : OptionGroup(name = "GitHub Options") {
    val githubHost by option()
        .help { "The GitHub host. This will be inferred by the remote URI if not specified." }
    val repoOwner by option()
        .help { "The GitHub owner name. This will be inferred by the remote URI if not specified." }
    val repoName by option()
        .help { "The GitHub repo name. This will be inferred by the remote URI if not specified." }
}

private fun ArgumentTransformContext.convertRefSpecString(refSpecString: String): RefSpec {
    val split = refSpecString.split(":")
    if (split.size > 2) fail("Invalid format for refspec: $refSpecString")
    return if (split.size == 2) {
        val (localObjectName, targetRefName) = split
        RefSpec(localObjectName, targetRefName)
    } else {
        RefSpec(DEFAULT_LOCAL_OBJECT, refSpecString)
    }
}

abstract class GitJasprCommand(help: String = "", hidden: Boolean = false) :
    CliktCommand(hidden = hidden, help = help) {
    private val workingDirectory = File(System.getProperty(WORKING_DIR_PROPERTY_NAME) ?: ".").findNearestGitDir()
        .canonicalFile
        .also { dir ->
            require(dir.exists()) { "${dir.absolutePath} does not exist" }
            require(dir.isDirectory) { "${dir.absolutePath} is not a directory" }
        }

    init {
        context {
            // Read all option values first from CONFIG_FILE_NAME in the user's home directory, overridden by
            // CONFIG_FILE_NAME in the working directory, overridden by any options provided on the command line.
            valueSource = ChainedValueSource(
                listOf(workingDirectory, File(System.getenv("HOME")))
                    .map { dir ->
                        PropertiesValueSource.from(
                            dir.resolve(CONFIG_FILE_NAME),
                            // don't add subcommand names to keys, see block comment in main entry point below
                            getKey = getKey(joinSubcommands = null),
                        )
                    },
            )
            helpFormatter = { MordantHelpFormatter(context = it, showDefaultValues = true) }
        }
    }

    private val missingTokenMessage = """
Hello! First time running Jaspr?

We couldn't find your GitHub PAT (personal access token).
You need to create one with read:org, read:user, repo,
and user:email permissions and provide it via one of
the following methods:

- Create a file named $CONFIG_FILE_NAME in your home
  directory with the following contents:
    githubToken=<your token here>
- Create a file named $CONFIG_FILE_NAME in your working
  directory with the following contents:
    githubToken=<your token here>
- Set the environment variable $GITHUB_TOKEN_ENV_VAR to
  your token

⚠️  NOTE ⚠️ : Please remember to enable SSO on your token 
if applicable. If in the future you change the scope of an
existing token, it will disable the SSO authorization so
you'll need to re-enable it again.
    """.trimIndent()

    private val githubToken by option(envvar = GITHUB_TOKEN_ENV_VAR)
        .transformAll(showAsRequired = true) { stringList ->
            stringList.lastOrNull()
                ?: throw PrintMessage(message = missingTokenMessage, statusCode = 1, printError = true)
        }
        .help {
            """
        A GitHub PAT (personal access token) with read:org, read:user, repo, and user:email permissions. Can be provided 
        via the per-user config file, a per-working copy config file, or the environment variable 
        $GITHUB_TOKEN_ENV_VAR.
            """.trimIndent()
        }

    private val gitHubOptions by GitHubOptions()

    private val logLevel: Level by option("-l", "--log-level")
        .choice(
            *listOf(OFF, ERROR, WARN, INFO, DEBUG, TRACE, ALL).map { level -> level.levelStr to level }.toTypedArray(),
            ignoreCase = true,
        )
        .default(INFO)
        .help { "The log level for the application." }

    private val logToFilesDelegate: OptionWithValues<Boolean, Boolean, Boolean> = option()
        .flag("--no-log-to-files", default = true)
        .help { "Write trace logs to directory specified by the ${logsDirectoryDelegate.names.first()} option" }

    private val logsDirectoryDelegate: OptionWithValues<File, File, File> = option()
        .file()
        .default(File("${System.getProperty("java.io.tmpdir")}/jaspr"))
        .help { "Trace logs will be written into this directory if ${logToFilesDelegate.names.first()} is enabled" }

    private val logToFiles: Boolean by logToFilesDelegate
    private val logsDirectory: File by logsDirectoryDelegate

    val defaultTargetRefDelegate = option()
        .default(DEFAULT_TARGET_REF)
        .help { "The name of the implicit target remote branch if not provided in the refspec." }
    val defaultTargetRef by defaultTargetRefDelegate

    private val remoteBranchPrefix by option()
        .default(DEFAULT_REMOTE_BRANCH_PREFIX)
        .help { "The prefix to use for all git jaspr created branches in the remote" }

    private val showConfig by option(hidden = true).flag("--no-show-config", default = false)
        .help { "Print the effective configuration to standard output (for debugging)" }

    private val remoteName by argument()
        .help {
            """
            The name of the git remote. This is used for git operations and for inferring github information if not 
            explicitly configured.
            """.trimIndent()
        }
        .default(DEFAULT_REMOTE_NAME)

    private val useCliGitClient by option().flag("--no-use-cli-git-client", default = false)
        .help {
            "Use the CLI client instead of the JGit client. This option is slightly slower but has better " +
                "compatibility with various SSH configurations such as OS X + ssh-agent."
        }

    val appWiring by lazy {
        val gitClient = if (useCliGitClient) {
            CliGitClient(workingDirectory, remoteBranchPrefix)
        } else {
            JGitClient(workingDirectory, remoteBranchPrefix)
        }
        val githubInfo = determineGithubInfo(gitClient)
        val config = Config(
            workingDirectory,
            remoteName,
            githubInfo,
            remoteBranchPrefix,
            logLevel,
            logsDirectory.takeIf { logToFiles },
        )

        DefaultAppWiring(githubToken, config, gitClient)
    }

    private fun determineGithubInfo(gitClient: GitClient): GitHubInfo {
        val host = gitHubOptions.githubHost
        val owner = gitHubOptions.repoOwner
        val name = gitHubOptions.repoName
        return if (host != null && owner != null && name != null) {
            GitHubInfo(host, owner, name)
        } else {
            val remoteUri = requireNotNull(gitClient.getRemoteUriOrNull(remoteName)) {
                "Couldn't determine URI for remote named $remoteName"
            }
            val fromUri = requireNotNull(extractGitHubInfoFromUri(remoteUri)) {
                "Couldn't infer github info from $remoteName URI: $remoteUri"
            }
            GitHubInfo(host ?: fromUri.host, owner ?: fromUri.owner, name ?: fromUri.name)
        }
    }

    override fun run() {
        val config = appWiring.config
        if (showConfig) {
            throw PrintMessage(appWiring.json.encodeToString(config))
        }
        val (loggingContext, logFile) = initLogging(config.logLevel, config.logsDirectory)
        runBlocking {
            val logger = Cli.logger
            try {
                doRun()
            } catch (e: GitJasprException) {
                logger.error("An error occurred", e)
                printError(e)
            } catch (e: Exception) {
                logger.logUnhandledException(e, logFile)
                printError(e)
            } finally {
                logger.trace("Stopping logging context")
                loggingContext.stop()
            }
        }
    }

    private fun initLogging(logLevel: Level, logFileDirectory: File?): Pair<LoggerContext, String?> {
        // NOTE: There is an initial "bootstrap" logging config set via logback.xml. This code makes assumptions based
        // on configuration in that file.
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        val fileAppender = if (logFileDirectory != null) createFileAppender(loggerContext, logFileDirectory) else null

        if (fileAppender != null) {
            rootLogger.addAppender(fileAppender)
            Cli.logger.debug("Logging to {}", fileAppender.file)
        }

        rootLogger.getAppender("STDOUT").apply {
            clearAllFilters()
            addFilter(
                ThresholdFilter().apply {
                    setLevel(logLevel.levelStr)
                    start()
                },
            )
        }

        return loggerContext to fileAppender?.file
    }

    private fun createFileAppender(loggerContext: LoggerContext, directory: File): FileAppender<ILoggingEvent> =
        RollingFileAppender<ILoggingEvent>()
            .apply {
                val fileAppender = this
                context = loggerContext
                name = "FILE"
                encoder = PatternLayoutEncoder().apply {
                    context = loggerContext
                    pattern = "%d{YYYY-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{5} - %msg%n"
                    start()
                }
                rollingPolicy = TimeBasedRollingPolicy<ILoggingEvent>().apply {
                    context = loggerContext
                    setParent(fileAppender)
                    fileNamePattern = "${directory.absolutePath}/jaspr.%d.log.txt"
                    maxHistory = 7
                    isCleanHistoryOnStart = true
                    start()
                }
                addFilter(
                    ThresholdFilter().apply {
                        setLevel(TRACE.levelStr)
                        start()
                    },
                )
                start()
            }

    private fun printError(e: Exception): Nothing = throw PrintMessage(e.message.orEmpty(), 255, true)

    private fun Logger.logUnhandledException(exception: Exception, logFile: String?) {
        error(exception.message, exception)
        error(
            "We're sorry, but you've likely encountered a bug. " +
                if (logFile != null) {
                    "Please open a bug report and attach the log file ($logFile)."
                } else {
                    "Please consider enabling file logging (see the ${logToFilesDelegate.names.first()} " +
                        "and ${logsDirectoryDelegate.names.first()} options) and opening a bug report " +
                        "with the log file attached."
                },
        )
    }

    abstract suspend fun doRun()
}

internal fun File.findNearestGitDir(): File {
    val parentFiles = generateSequence(canonicalFile) { it.parentFile }
    return checkNotNull(parentFiles.firstOrNull { it.resolve(".git").isDirectory }) {
        "Can't find a git dir in $canonicalFile or any of its parent directories"
    }
}

object Cli {
    val logger: Logger = LoggerFactory.getLogger(Cli::class.java)

    @JvmStatic
    fun main(args: Array<out String>) {
        /*
        Explanation for why NoOpCliktCommand is used here:
        The CLI follows the conventional pattern of `git jaspr <command>`. Each subcommand (ex. `status`, `push`, etc.)
        shares common bootstrap code that depends on command options/arguments. Clikt supports nesting commands and
        offers a mechanism for passing information between parent (`git-jaspr`) and child (ex. `push`), but this
        mechanism is not ideal for our desired UI, due to the way Clikt parses command specific options positionally.
        We want to support `git jaspr status origin` and `git jaspr push origin` where `origin` is a remote name, rather
        than `git jaspr --remote-name origin status` and `git jaspr --remote-name origin push`. In order to do this,
        we're using NoOpCliktCommand for `git-jaspr`, and the common options/arguments/bootstrap code for the subcommands
        is handled via an abstract base class.
         */
        NoOpCliktCommand(name = "git jaspr")
            .versionOption("v7-beta")
            .subcommands(
                listOf(
                    Status(),
                    Push(),
                    Merge(),
                    AutoMerge(),
                    Clean(),
                    InstallCommitIdHook(),
                    NoOp(),
                ),
            )
            .main(args)
    }
}

const val WORKING_DIR_PROPERTY_NAME = "git-jaspr-working-dir"
const val CONFIG_FILE_NAME = ".git-jaspr.properties"
const val DEFAULT_LOCAL_OBJECT = GitClient.HEAD
const val DEFAULT_TARGET_REF = "main"
const val DEFAULT_REMOTE_NAME = "origin"
const val COMMIT_ID_LABEL = "commit-id"
private const val GITHUB_TOKEN_ENV_VAR = "GIT_JASPR_TOKEN"
