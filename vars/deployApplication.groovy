#!/usr/bin/env groovy

def call(Map args) {
    def targetEnv = args.get('targetEnv', '').toLowerCase()
    def imageToDeploy = args.get('imageToDeploy', '')
    def appPort = ''

    if (targetEnv == 'main') {
        appPort = '8082'
    } else if (targetEnv == 'dev') {
        appPort = '8081'
    } else {
        error "Invalid target environment specified: ${targetEnv}. Must be 'main' or 'dev'."
    }

    if (imageToDeploy.isEmpty()) {
        error "Docker image to deploy was not specified."
    }

    def containerName = "app-${targetEnv}"

    echo "Deploying image '${imageToDeploy}' to '${targetEnv}' environment on port ${appPort}."

    sh """
        if [ \$(docker ps -a -q -f name=${containerName}) ]; then
            echo "Stopping and removing existing container: ${containerName}"
            docker stop ${containerName}
            docker rm ${containerName}
        fi
    """
    sh "docker run -d --name ${containerName} -p ${appPort}:80 ${imageToDeploy}"
    echo "Successfully deployed container '${containerName}'."
} 
