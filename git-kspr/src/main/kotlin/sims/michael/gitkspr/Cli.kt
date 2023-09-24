package sims.michael.gitkspr

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.sources.ChainedValueSource
import com.github.ajalt.clikt.sources.PropertiesValueSource
import com.github.ajalt.clikt.sources.ValueSource.Companion.getKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException

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

    override fun run() {
        super.run()
        runCatching {
            runBlocking {
                appWiring.gitKspr.push(refSpec)
            }
        }
    }
}

// git kspr status [remote-name] [local-object]
class Status : GitKsprCommand() { // Common options/arguments are inherited from the superclass
    override fun run() {
        super.run()
        TODO("Status")
    }
}

// git kspr merge [remote-name] [local-object]
class Merge : GitKsprCommand() { // Common options/arguments are inherited from the superclass
    override fun run() {
        super.run()
        TODO("Merge")
    }
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
        .default("origin")

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

    fun printError(e: Exception): Nothing = throw PrintMessage(e.message.orEmpty(), 255, true)

    inline fun runCatching(block: () -> Unit) {
        try {
            block()
        } catch (e: GitKsprException) {
            printError(e)
        } catch (e: IllegalStateException) {
            printError(e)
        } catch (e: IllegalArgumentException) {
            printError(e)
        }
    }

    override fun run() {
        if (showConfig) {
            throw PrintMessage(appWiring.json.encodeToString(appWiring.config))
        }
    }
}

object Cli {
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
const val DEFAULT_LOCAL_OBJECT = "HEAD"
const val DEFAULT_TARGET_REF = "main"
private const val GITHUB_TOKEN_ENV_VAR = "GIT_KSPR_TOKEN"
