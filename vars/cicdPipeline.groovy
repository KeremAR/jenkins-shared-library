def call() {
    pipeline {
        agent any

        environment {
            DOCKERHUB_USER = "keremar"
            DOCKER_CREDS   = credentials('dockerhub-credentials')
        }

        stages {
            stage('Prepare Environment') {
                steps {
                    script {
                        def logoFilePath = ''
                        def dockerImageName = ''
                        if (env.BRANCH_NAME == 'main') {
                            dockerImageName = "${env.DOCKERHUB_USER}/nodemain:latest"
                            logoFilePath = 'src/logo-main.svg'
                        } else if (env.BRANCH_NAME == 'dev') {
                            dockerImageName = "${env.DOCKERHUB_USER}/nodedev:latest"
                            logoFilePath = 'src/logo-dev.svg'
                        }
                        env.DOCKER_IMAGE_NAME = dockerImageName
                        env.LOGO_FILE_PATH = logoFilePath
                        echo "Image name set to: ${env.DOCKER_IMAGE_NAME}"
                        echo "Logo file path set to: ${env.LOGO_FILE_PATH}"
                    }
                }
            }
            
            stage('Build and Push') {
                agent {
                    docker {
                        image 'docker:20.10.12'
                        args '-u root -v /var/run/docker.sock:/var/run/docker.sock'
                    }
                }
                stages {
                    stage('Lint Dockerfile') {
                        steps {
                            sh 'docker run --rm -i hadolint/hadolint < Dockerfile'
                        }
                    }

                    stage('Test') {
                        steps {
                            sh 'npm install --cache .npm-cache'
                            sh 'npm test -- --watchAll=false'
                        }
                    }
                    stage('Build Docker Image') {
                        steps {
                            sh "docker build --build-arg LOGO_FILE=${env.LOGO_FILE_PATH} -t ${env.DOCKER_IMAGE_NAME} ."
                        }
                    }
                    stage('Scan and Push if Not a PR') {
                        when {
                            anyOf {
                                branch 'main'
                                branch 'dev'
                            }
                        }
                        steps {
                            script {
                                sh "docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v ${pwd()}:/app -w /app aquasec/trivy:latest image --cache-dir .trivy-cache --exit-code 0 --severity HIGH,CRITICAL ${env.DOCKER_IMAGE_NAME}"
                                sh 'echo $DOCKER_CREDS_PSW | docker login -u $DOCKER_CREDS_USR --password-stdin'
                                sh "docker push ${env.DOCKER_IMAGE_NAME}"
                            }
                        }
                    }
                }
            }
        }

        post {
            success {
                script {
                    if (env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'dev') {
                        echo "Build successful. Triggering deployment for branch ${env.BRANCH_NAME}..."
                        deployApplication(
                            targetEnv: env.BRANCH_NAME,
                            imageToDeploy: env.DOCKER_IMAGE_NAME
                        )
                    }
                }
            }
            always {
                echo "Cleaning up..."
                script {
                    if (env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'dev') {
                        echo "Logging out from Docker Hub..."
                        sh 'docker logout'
                    }
                }
                cleanWs()
            }
        }
    }
}
