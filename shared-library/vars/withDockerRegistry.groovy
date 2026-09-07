/**
 * Run Docker registry operations with workspace-scoped credentials.
 * Docker must not persist Jenkins credentials in the agent user's home directory.
 */
def call(Map config, Closure body) {
    def registry = config.dockerRegistry
    def dockerConfig = "${pwd(tmp: true)}/docker-auth"
    def result

    try {
        sh "rm -rf '${dockerConfig}' && mkdir -p '${dockerConfig}' && chmod 700 '${dockerConfig}'"
        withEnv(["DOCKER_CONFIG=${dockerConfig}"]) {
            withCredentials([usernamePassword(
                credentialsId: config.dockerCredId,
                usernameVariable: 'DOCKER_USER',
                passwordVariable: 'DOCKER_PASS'
            )]) {
                sh "printf '%s' \"\$DOCKER_PASS\" | docker login '${registry}' -u \"\$DOCKER_USER\" --password-stdin >/dev/null"
            }
            result = body()
        }
    } finally {
        sh "rm -rf '${dockerConfig}'"
    }
    return result
}
