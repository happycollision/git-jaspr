package sims.michael.gitkspr

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Level.*
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.core.rolling.RollingFileAppender
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.sources.ChainedValueSource
import com.github.ajalt.clikt.sources.PropertiesValueSource
import com.github.ajalt.clikt.sources.ValueSource.Companion.getKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

/*
git kspr push [remote-name] [[local-object:]target-ref]

If not provided:
remote-name: origin
local-ref: HEAD
target-branch: main
 */
class Push : GitKsprCommand() { // Common options/arguments are inherited from the superclass
    private val refSpec by argument()
        .convert { refSpecString ->
            val split = refSpecString.split(":")
            if (split.size > 2) fail("Invalid format for refspec: $refSpecString")
            if (split.size == 2) {
                val (localObjectName, targetRefName) = split
                RefSpec(localObjectName, targetRefName)
            } else {
                RefSpec(DEFAULT_LOCAL_OBJECT, refSpecString)
            }
        }
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

    override suspend fun doRun() = appWiring.gitKspr.push(refSpec)
}

// git kspr status [remote-name] [local-object]
class Status : GitKsprCommand() { // Common options/arguments are inherited from the superclass
    override suspend fun doRun() = TODO("Status")
}

// git kspr merge [remote-name] [local-object]
class Merge : GitKsprCommand() { // Common options/arguments are inherited from the superclass
    override suspend fun doRun() = TODO("Merge")
}

private class GitHubOptions : OptionGroup(name = "GitHub Options") {
    val githubHost by option()
        .help { "The GitHub host. This will be inferred by the remote URI if not specified." }
    val repoOwner by option()
        .help { "The GitHub owner name. This will be inferred by the remote URI if not specified." }
    val repoName by option()
        .help { "The GitHub repo name. This will be inferred by the remote URI if not specified." }
}

abstract class GitKsprCommand : CliktCommand() {
    private val workingDirectory = File(System.getProperty(WORKING_DIR_PROPERTY_NAME) ?: ".")
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

    private val githubToken by option(envvar = GITHUB_TOKEN_ENV_VAR).required().help {
        """
        A GitHub PAT (personal access token) with read:org, read:user, and user:email permissions. Can be provided 
        via the per-user config file, a per-working copy config file, or the environment variable 
        $GITHUB_TOKEN_ENV_VAR.
        """.trimIndent()
    }

    private val gitHubOptions by GitHubOptions()

    private val logLevel: Level? by option("-l", "--log-level")
        .choice(
            *listOf(OFF, ERROR, WARN, INFO, DEBUG, TRACE, ALL).map { level -> level.levelStr to level }.toTypedArray(),
            ignoreCase = true,
        )
        .help { "The log level for the application. (default: INFO)" }

    val defaultTargetRefDelegate = option()
        .default(DEFAULT_TARGET_REF)
        .help { "The name of the implicit target remote branch if not provided in the refspec." }
    val defaultTargetRef by defaultTargetRefDelegate

    private val showConfig by option().flag("--no-show-config", default = false)
        .help { "Print the effective configuration to standard output (for debugging)" }

    private val remoteName by argument()
        .help {
            """
            The name of the git remote. This is used for git operations and for inferring github information if not 
            explicitly configured.
            """.trimIndent()
        }
        .default(DEFAULT_REMOTE_NAME)

    val appWiring by lazy {
        val gitClient = JGitClient(workingDirectory)
        val githubInfo = determineGithubInfo(gitClient)
        val config = Config(workingDirectory, remoteName, githubInfo)

        DefaultAppWiring(githubToken, config, gitClient)
    }

    private fun determineGithubInfo(jGitClient: JGitClient): GitHubInfo {
        val host = gitHubOptions.githubHost
        val owner = gitHubOptions.repoOwner
        val name = gitHubOptions.repoName
        return if (host != null && owner != null && name != null) {
            GitHubInfo(host, owner, name)
        } else {
            val remoteUri = requireNotNull(jGitClient.getRemoteUriOrNull(remoteName)) {
                "Couldn't determine URI for remote named $remoteName"
            }
            val fromUri = requireNotNull(extractGitHubInfoFromUri(remoteUri)) {
                "Couldn't infer github info from $remoteName URI: $remoteUri"
            }
            GitHubInfo(host ?: fromUri.host, owner ?: fromUri.owner, name ?: fromUri.name)
        }
    }

    private fun printError(e: Exception): Nothing = throw PrintMessage(e.message.orEmpty(), 255, true)

    override fun run() {
        if (showConfig) {
            throw PrintMessage(appWiring.json.encodeToString(appWiring.config))
        }
        val (loggingContext, logFile) = initLogging(logLevel)
        runBlocking {
            val logger = Cli.logger
            try {
                doRun()
            } catch (e: GitKsprException) {
                printError(e)
            } catch (e: Exception) {
                logger.error(e.message)
                logger.error(
                    "We're sorry, but you've likely encountered a bug. " +
                        "Please open a bug report and attach the log file: {}",
                    logFile,
                )
                printError(e)
            } finally {
                logger.trace("Stopping logging context")
                loggingContext.stop()
            }
        }
    }

    private fun initLogging(logLevel: Level?): Pair<LoggerContext, String> {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = loggerContext.getLogger("ROOT")
        val fileAppender = rootLogger.getAppender("FILE") as RollingFileAppender<*>

        Cli.logger.debug("logging to {}", fileAppender.file)

        if (logLevel != null) {
            rootLogger.getAppender("STDOUT").apply {
                clearAllFilters()
                addFilter(
                    ThresholdFilter().apply {
                        setLevel(logLevel.levelStr)
                        start()
                    },
                )
            }
        }

        return loggerContext to fileAppender.file
    }

    abstract suspend fun doRun()
}

object Cli {
    val logger: Logger = LoggerFactory.getLogger(Cli::class.java)

    @JvmStatic
    fun main(args: Array<out String>) {
        /*
        Explanation for why NoOpCliktCommand is used here:
        The CLI follows the conventional pattern of `git kspr <command>`. Each subcommand (ex. `status`, `push`, etc.)
        shares common bootstrap code that depends on command options/arguments. Clikt supports nesting commands and
        offers a mechanism for passing information between parent (`git-kspr`) and child (ex. `push`), but this
        mechanism is not ideal for our desired UI, due to the way Clikt parses command specific options positionally.
        We want to support `git kspr status origin` and `git kspr push origin` where `origin` is a remote name, rather
        than `git kspr --remote-name origin status` and `git kspr --remote-name origin push`. In order to do this,
        we're using NoOpCliktCommand for `git-kspr`, and the common options/arguments/bootstrap code for the subcommands
        is handled via an abstract base class.
         */
        NoOpCliktCommand(name = "git kspr")
            .subcommands(
                listOf(
                    Status(),
                    Push(),
                    Merge(),
                ),
            )
            .main(args)
    }
}

const val WORKING_DIR_PROPERTY_NAME = "git-kspr-working-dir"
const val CONFIG_FILE_NAME = ".git-kspr.properties"
const val DEFAULT_LOCAL_OBJECT = JGitClient.HEAD
const val DEFAULT_TARGET_REF = "main"
const val DEFAULT_REMOTE_NAME = "origin"
const val COMMIT_ID_LABEL = "commit-id"
private const val GITHUB_TOKEN_ENV_VAR = "GIT_KSPR_TOKEN"
