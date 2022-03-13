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
                steps {
                    script { 
                        def libLoader = new org.mauro.LibLoader()
                        libLoader.loadLib()
                        agentImage = templateLib.getDefaultAgent()

                        templateInfo = input message: 'choose temlate', ok: 'Next',
                        parameters: [
                            choice(choices: templateLib.getTemplates(), name: 'template', description: 'template type'),
                            choice(choices: ['gitHub', 'bitBucket'], name: 'gitDstRemote', description: 'git destination remote'),
                            string(defaultValue: '', name: 'serviceName', trim: true, description: 'project name')]

                        sh "echo 'template: ${templateInfo.template}'"
                        sh "echo 'git remote: ${templateInfo.gitDstRemote}'"
                        sh "echo 'service name: ${templateInfo.serviceName}'"
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
                            if ("${templateInfo.serviceName}" == '') {   
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
                                jenkinsLib.createProjectIfNotExits("${input_parameters.project}")
                                projectName = "${input_parameters.project}"
                            } else {
                                projectName = "${input_parameters.projects}"
                            }
                            sh "echo 'project created: ${projectName}'"
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
                steps {
                    script {
                        gitLib.validateEnvVars("${templateInfo.gitDstRemote}")
                        gitLib.createProjectIfNotExitsIfAppl("${templateInfo.gitDstRemote}", "${projectName}")
                        if (gitLib.isRepositoryExits("${templateInfo.gitDstRemote}", "${templateInfo.serviceName}")) {
                            error('repository already exits...!')
                        }
                    }
                }
            }
            stage('Preparing templating type') {
                when {
                    expression { 
                        return params.manualTrigger
                    }
                }
                steps {
                    script {
                        branch = templateLib.getBranch("${templateInfo.template}")
                        template =  templateLib.getTemplateType("${templateInfo.template}")
                        agentImage =  templateLib.getAgentByTemplate("${templateInfo.template}")
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
                    docker "${agentImage}"
                }
                steps {
                    script {
                        gitLib.cloneRepoWithBranch("${branch}", "${template}")
                        sh "echo ${templateInfo.serviceName}"
                        sh "rm -rf ${templateInfo.serviceName}"
                        sh "./${template}/prepare.sh ${templateInfo.serviceName}"
                        sh "mv ${template} ${templateInfo.serviceName}"
                        sh "rm ${templateInfo.serviceName}/prepare.sh"
                        jenkinsLib.stash('template', "${templateInfo.serviceName}/**/*", "${templateInfo.serviceName}/.git", false)
                    }
                }
            }
            stage('Prepare git') {
                when {
                    expression { 
                        return params.manualTrigger
                    }
                }
                steps {
                    script { 
                        unstash 'template'
                        dir("${templateInfo.serviceName}") {
                            gitLib.createRepo("${templateInfo.gitDstRemote}", "${templateInfo.serviceName}", "${projectName}")
                            jenkinsLib.createJenkinsPipelineFileWithLib("${templateLib.getCiPipeline()}", "${templateLib.getCiVersion()}")
                            gitLib.initRepo("${templateInfo.gitDstRemote}", "${GIT_EMAIL}", "${GIT_USER}", "${templateInfo.serviceName}", 'origin')
                            gitLib.commitAndPushRepo('origin', 'develop', 'first draft')
                        }
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
                        build job: 'create-ci-cd-jobs', wait: true, parameters: [string(name: 'gitDstRemote', value: String.valueOf("${templateInfo.gitDstRemote}")),
                                                                                        string(name: 'projectName', value: String.valueOf("${projectName}")),
                                                                                        string(name: 'serviceName', value: String.valueOf("${templateInfo.serviceName}"))]
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