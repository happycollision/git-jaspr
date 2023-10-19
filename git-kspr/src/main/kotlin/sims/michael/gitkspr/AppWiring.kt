package sims.michael.gitkspr

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import kotlinx.serialization.json.Json
import sims.michael.gitkspr.graphql.GitHubGraphQLClient
import java.net.URL

interface AppWiring {
    val gitKspr: GitKspr
    val config: Config
    val json: Json
    val gitClient: JGitClient
}

class DefaultAppWiring(
    private val githubToken: String,
    override val config: Config,
    override val gitClient: JGitClient,
) : AppWiring {
    private val bearerTokens by lazy {
        BearerTokens(githubToken, githubToken)
    }

    private val httpClient by lazy {
        HttpClient(engineFactory = CIO)
            .config {
                install(Auth) {
                    bearer {
                        loadTokens {
                            bearerTokens
                        }
                    }
                }
            }
    }

    val graphQLClient: GraphQLClient<*> by lazy {
        GitHubGraphQLClient(GraphQLKtorClient(URL("https://api.github.com/graphql"), httpClient))
    }

    val gitHubClient: GitHubClient by lazy {
        GitHubClient(graphQLClient, config.gitHubInfo, config.remoteBranchPrefix)
    }

    override val json: Json = Json {
        prettyPrint = true
    }

    override val gitKspr: GitKspr by lazy { GitKspr(gitHubClient, gitClient, config) }
}
