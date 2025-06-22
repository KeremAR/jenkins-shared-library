def call() {
    pipeline {
        agent any

        environment {
            DOCKERHUB_USER = "keremar"
            DOCKER_CREDS   = credentials('dockerhub-credentials')
            DOCKER_IMAGE_NAME = ''
            // Defines where the JUnit reports will be stored
            JEST_JUNIT_OUTPUT_DIR = "reports/junit"
        }

        stages {
            stage('Prepare Environment') {
                steps {
                    script {
                        def logoFilePath = ''
                        if (env.BRANCH_NAME == 'main') {
                            env.DOCKER_IMAGE_NAME = "${DOCKERHUB_USER}/nodemain:latest"
                            logoFilePath = 'src/logo-main.svg'
                        } else if (env.BRANCH_NAME == 'dev') {
                            env.DOCKER_IMAGE_NAME = "${DOCKERHUB_USER}/nodedev:latest"
                            logoFilePath = 'src/logo-dev.svg'
                        } else {
                            env.DOCKER_IMAGE_NAME = "local-build/${env.BRANCH_NAME}"
                            logoFilePath = 'src/logo.svg' // Default logo for other branches
                        }
                        env.LOGO_FILE_PATH = logoFilePath
                        echo "Image name set to: ${env.DOCKER_IMAGE_NAME}"
                        echo "Logo file path set to: ${env.LOGO_FILE_PATH}"
                    }
                }
            }

            stage('Test Application and Collect Reports') {
                agent {
                    docker { image 'node:16-alpine' }
                }
                steps {
                    sh 'npm install'
                    // Run tests and generate JUnit report
                    sh 'npm test -- --ci --reporters=default --reporters=jest-junit'
                }
                post {
                    always {
                        // Publish test results
                        junit testResults: "${JEST_JUNIT_OUTPUT_DIR}/*.xml"
                    }
                }
            }
            
            stage('Build and Push') {
                agent {
                    docker {
                        image 'docker:20.10.12'
                        args '-v /var/run/docker.sock:/var/run/docker.sock'
                    }
                }
                stages {
                    stage('Lint Dockerfile') {
                        steps {
                            // We need to have Docker available to run hadolint container
                            sh 'docker run --rm -i hadolint/hadolint < Dockerfile'
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
                                // Scan image
                                sh "docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest image --exit-code 0 --severity HIGH,CRITICAL ${env.DOCKER_IMAGE_NAME}"
                                
                                // Push image
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
                    // Only try to logout if logged in
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
