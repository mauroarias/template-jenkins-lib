def call(body) {
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()

    pipeline {
        agent {
            docker {
                image 'ghcr.io/mauroarias/inbound-agent:0.0.1'
            }
        }
        options {
            timestamps()
            disableConcurrentBuilds()
        }
        parameters {
            booleanParam(defaultValue: false, name: 'manualTrigger', description: 'manual trigger')
            string(name: 'gitDstRemote', description: 'dont use this entry in manual steps')
            string(name: 'projectName', description: 'dont use this entry in manual steps')
            string(name: 'serviceName', description: 'dont use this entry in manual steps')
        }    
        stages {
            stage('Initialize') {
                when {
                    expression { 
                        return params.manualTrigger || (!params.gitDstRemote.equals('') && !params.projectName.equals('') && !params.serviceName.equals(''))
                    }
                }
                steps {
                    script { 
                        sh "echo 'manual trigger: ${params.manualTrigger}'"
                        new org.mauro.LibLoader().loadLib()
                        jenkinsLib.downloadJenkinsCli()
                    }
                }
            }
            stage('validate') {
                when {
                    expression { 
                        return (!params.gitDstRemote.equals('') && !params.projectName.equals('') && !params.serviceName.equals(''))
                    }
                }
                steps {
                    script { 
                        gitDstRemote = params.gitDstRemote
                        projectName = params.projectName
                        serviceName = params.serviceName
                        sh "echo 'manual trigger: ${params.manualTrigger}'"
                        sh "echo 'git repository remote: ${gitDstRemote}'"
                        sh "echo 'project: ${projectName}'"
                        sh "echo 'service name: ${serviceName}'"
                    }
                }
            }
            stage('choose git remote') {
                when {
                    expression { 
                        return params.manualTrigger
                    }
                }
                steps {
                    timeout(time: 3, unit: 'MINUTES') {
                        script {
                            inputGitRemote = input message: 'choose git remote', ok: 'Next',
                            parameters: [
                                choice(name: 'gitDstRemoteCi', choices: ['gitHub', 'bitBucket']),
                                string(name: 'x')]
                            gitDstRemote = inputGitRemote.gitDstRemoteCi
                            sh "echo 'git repository remote: ${gitDstRemote}'"
                        }
                    }
                }
            }
            stage('choose project') {
                when {
                    expression { 
                        return params.manualTrigger
                    }
                }
                environment {
                    JENKINS_CRED = credentials('user-pass-credential-jenkins-credentials')
                }
                steps {
                    timeout(time: 3, unit: 'MINUTES') {
                        script { 
                            inputProjectsCi = input message: 'choose project', ok: 'Next',
                            parameters: [
                                choice(choices: jenkinsLib.getprojects(), name: 'projectsCi', description: 'choose project'),
                                booleanParam(defaultValue: false, name: 'newProjectCi', description: 'create a new project'),
                                string(name: 'project', trim: true, required: true, description: 'new project name')]
                            if (inputProjectsCi.newProjectCi) {
                                if ("${inputProjectsCi.project}" == '') {   
                                    error('new project must be defined...!')
                                }
                                jenkinsLib.createProjectIfNotExits(inputProjectsCi.project)
                                projectName = inputProjectsCi.project
                            } else {
                                projectName = inputProjectsCi.projectsCi
                            }
                            sh "echo 'project: ${projectName}'"
                        }
                    }
                }
            }
            stage('choose service name') {
                when {
                    expression { 
                        return params.manualTrigger
                    }
                }
                environment {
                    GIT_HUB_CRED = credentials('user-pass-credential-github-credentials')
                    BIT_BUCKET_CRED = credentials('user-pass-credential-bitbucket-credentials')
                }
                steps {
                    timeout(time: 3, unit: 'MINUTES') {
                        script {
                            inputRepo = input message: "choose service name", ok: 'Next',
                            parameters: [
                                choice(choices: gitLib.getRepos(gitDstRemote, projectName), name: 'repo', description: 'choose repository'),
                                string(name: 'x')]
                            serviceName = inputRepo.repo
                            sh "echo 'service name: ${serviceName}'"
                        }
                    }
                }
            }
            stage('Create jobs') {
                when {
                    expression { 
                        return params.manualTrigger || (!params.gitDstRemote.equals('') && !params.projectName.equals('') && !params.serviceName.equals(''))
                    }
                }
                parallel {
                    stage('Ci job') {
                        steps {
                            script {
                                build job: 'create-ci-job', wait: true, parameters: [string(name: 'gitDstRemote', value: String.valueOf(gitDstRemote)),
                                                                                     string(name: 'projectName', value: String.valueOf(projectName)),
                                                                                     string(name: 'serviceName', value: String.valueOf(serviceName))]
                            }
                        }
                    }
                    stage('cd job') {
                        steps {
                            script {
                                build job: 'create-cd-job', wait: true, parameters: [string(name: 'gitDstRemote', value: String.valueOf(gitDstRemote)),
                                                                                     string(name: 'projectName', value: String.valueOf(projectName)),
                                                                                     string(name: 'serviceName', value: String.valueOf(serviceName))]
                            }
                        }
                    }
                }
            }
        }   
        post {
            // Clean after build
            always {
                cleanWs()
                deleteDir()
            }
        }
    }
}