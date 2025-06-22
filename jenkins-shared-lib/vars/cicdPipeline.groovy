def call() {
    pipeline {
        agent any

        environment {
            DOCKERHUB_USER = "keremar"
            DOCKERHUB_REPO = "epam-jenkins-lab"
            DOCKER_CREDS   = credentials('dockerhub-credentials')
        }

        stages {
            stage('Prepare Environment') {
                steps {
                    script {
                        def dockerImageName = ''
                        def logoFilePath = ''

                        if (env.BRANCH_NAME == 'main') {
                            dockerImageName = "${env.DOCKERHUB_USER}/${env.DOCKERHUB_REPO}:main-v1.0"
                            logoFilePath    = 'src/logo-main.svg'
                        } else if (env.BRANCH_NAME == 'dev') {
                            dockerImageName = "${env.DOCKERHUB_USER}/${env.DOCKERHUB_REPO}:dev-v1.0"
                            logoFilePath    = 'src/logo-dev.svg'
                        }

                        env.DOCKER_IMAGE_NAME = dockerImageName
                        env.LOGO_FILE_PATH    = logoFilePath
                    }
                }
            }

            stage('Change Logo') {
                steps {
                    sh "cp -f ${env.LOGO_FILE_PATH} src/logo.svg"
                }
            }

            stage('Build Docker Image') {
                steps {
                    sh "docker build -t ${env.DOCKER_IMAGE_NAME} ."
                }
            }

            stage('Scan Docker Image for Vulnerabilities') {
                steps {
                    sh "trivy image --timeout 15m --skip-dirs /app/node_modules --scanners vuln --exit-code 0 --severity HIGH,CRITICAL ${env.DOCKER_IMAGE_NAME}"
                }
            }

            stage('Push to Docker Hub') {
                steps {
                    script {
                        sh 'echo $DOCKER_CREDS_PSW | docker login -u $DOCKER_CREDS_USR --password-stdin'
                        sh "docker push ${env.DOCKER_IMAGE_NAME}"
                    }
                }
            }
        }

        post {
            success {
                script {
                    echo "Build successful. Triggering deployment..."
                    if (env.BRANCH_NAME == 'main') {
                        build job: 'Deploy_to_main', wait: false, parameters: [
                            string(name: 'IMAGE_TO_DEPLOY', value: env.DOCKER_IMAGE_NAME)
                        ]
                    } else if (env.BRANCH_NAME == 'dev') {
                        build job: 'Deploy_to_dev', wait: false, parameters: [
                            string(name: 'IMAGE_TO_DEPLOY', value: env.DOCKER_IMAGE_NAME)
                        ]
                    }
                }
            }

            always {
                echo "Logging out from Docker Hub..."
                sh 'docker logout'
                cleanWs()
            }
        }
    }
} 
