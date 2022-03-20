def call(body) {
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()

    pipeline {
        agent any
        options {
            timestamps()
            disableConcurrentBuilds()
        }
        parameters {
            booleanParam(defaultValue: false, name: 'manualTrigger', description: 'manual trigger')
        }    
        stages {
            stage('Initialize') {
                when {
                    expression { 
                        return params.manualTrigger
                    }
                }
                steps {
                    script { 
                        new org.mauro.LibLoader().loadLib()

                        templateInfo = input message: 'choose temlate', ok: 'Next',
                        parameters: [
                            choice(choices: templateLib.getTemplates(), name: 'template', description: 'template type'),
                            choice(choices: ['gitHub', 'bitBucket'], name: 'gitDst', description: 'git destination remote'),
                            string(defaultValue: '', name: 'service', trim: true, description: 'project name')]
                        templateFullName = templateInfo.template
                        gitDstRemote = templateInfo.gitDst
                        serviceName = templateInfo.service
                        agentName=templateLib.getDefaultAgent()

                        sh "echo 'template: ${templateFullName}'"
                        sh "echo 'git remote: ${gitDstRemote}'"
                        sh "echo 'service name: ${serviceName}'"
                        sh "echo 'manual trigger: ${params.manualTrigger}'"
                    }
                }
            }
            stage('Choose project') {
                when {
                    expression { 
                        return params.manualTrigger
                    }
                }
                steps {
                    timeout(time: 3, unit: 'MINUTES') {
                        script { 
                            if ("${serviceName}" == '') {   
                                error('service name must be defined...!')
                            }
                            jenkinsLib.downloadJenkinsCli()
                            input_parameters = input message: 'choose project', ok: 'Next',
                            parameters: [
                                choice(choices: jenkinsLib.getprojects(), name: 'projects', description: 'choose project'),
                                booleanParam(defaultValue: false, name: 'newProject', description: 'create a new project'),
                                string(defaultValue: '', name: 'project', trim: true, required: true, description: 'new project name')]
                            if (input_parameters.newProject) {
                                if ("${input_parameters.project}" == '') {   
                                    error('new project must be defined...!')
                                }
                                jenkinsLib.createProjectIfNotExits(input_parameters.project)
                                projectName=input_parameters.project
                            } else {
                                projectName=input_parameters.projects
                            }
                            sh "echo 'project created: ${projectName}'"
                            agentName=templateLib.getTemplateAgent(templateFullName)
                        }
                    }
                }
            }
            stage('Getting git repository') {
                when {
                    expression { 
                        return params.manualTrigger
                    }
                }
                agent {
                    docker "${agentName}"
                }
                environment {
                    GIT_HUB_CRED = credentials('user-pass-credential-github-credentials')
                    BIT_BUCKET_CRED = credentials('user-pass-credential-bitbucket-credentials')
                }
                steps {
                    script {
                        templateLib.gettingGitRepository(gitDstRemote, projectName, serviceName)
                    }
                }
            }
            stage('Applying template') {
                when {
                    expression { 
                        return params.manualTrigger
                    }
                }
                agent {
                    docker "${agentName}"
                }
                environment {
                    GIT_HUB_CRED = credentials('user-pass-credential-github-credentials')
                    BIT_BUCKET_CRED = credentials('user-pass-credential-bitbucket-credentials')
                }
                steps {
                    script {
                        templateLib.applyGitRepository("${gitDstRemote}", "${serviceName}", "${templateFullName}", "${projectName}")
                    }
                }
            }
            stage('Create jenkins jobs') {
                when {
                    expression { 
                        return params.manualTrigger
                    }
                }
                steps {
                    script {
                        build job: 'create-ci-cd-jobs', wait: true, parameters: [string(name: 'gitDstRemote', value: String.valueOf("${gitDstRemote}")),
                                                                                        string(name: 'projectName', value: String.valueOf("${projectName}")),
                                                                                        string(name: 'serviceName', value: String.valueOf("${serviceName}"))]
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
