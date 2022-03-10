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
            choice(choices: ['maven java8', 'maven java11'], name: 'template', description: 'template type')
            choice(choices: ['gitHub', 'bitBucket'], name: 'gitRemote', description: 'git remote')
            string(defaultValue: '', name: 'serviceName', trim: true, description: 'project name')
            booleanParam(defaultValue: false, name: 'manualTrigger', description: 'manual trigger')
        }    
        stages {
            stage('Initialize') {
                steps {
                    script { 
                        sh "echo 'template: ${params.template}'"
                        sh "echo 'git remote: ${params.gitRemote}'"
                        sh "echo 'service name: ${params.serviceName}'"
                        sh "echo 'manual trigger: ${params.manualTrigger}'"
                        def loadingLib = new org.mauro.LibLoader()
                        loadingLib.loadLib()
                        agentImage = 'alpine'
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
                            if ("${params.serviceName}" == '') {   
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
                        gitLib.validateEnvVars("${params.gitRemote}")
                        gitLib.createProjectIfNotExitsIfAppl("${params.gitRemote}", "${projectName}")
                        if (gitLib.isRepositoryExits("${params.gitRemote}", "${params.serviceName}")) {
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
                        branch = templateLib.getBranch("${params.template}")
                        template =  templateLib.getTemplateType("${params.template}")
                        agentImage =  templateLib.getAgentByTemplate("${params.template}")
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
                        gitLib.cloneRepoWithBranch("${params.gitRemote}", "${branch}", "${template}")
                        sh "echo ${params.serviceName}"
                        sh "rm -rf ${params.serviceName}"
                        sh "./${template}/prepare.sh ${params.serviceName}"
                        sh "mv ${template} ${params.serviceName}"
                        sh "rm ${params.serviceName}/prepare.sh"
                        jenkinsLib.stash('template', "${params.serviceName}/**/*", "${params.serviceName}/.git", false)
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
                        dir("${params.serviceName}") {
                            gitLib.createRepo("${params.gitRemote}", "${params.serviceName}", "${projectName}")
                            jenkinsLib.createJenkinsPipelineFileWithLib("${templateLib.getCiPipeline()}", "${templateLib.getCiVersion()}")
                            gitLib.initRepo("${params.gitRemote}", "${GIT_EMAIL}", "${GIT_USER}", "${params.serviceName}", 'origin')
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
                        build job: 'create-ci-cd-jobs', wait: true, parameters: [string(name: 'gitRemote', value: String.valueOf("${params.gitRemote}")),
                                                                                        string(name: 'projectName', value: String.valueOf("${projectName}")),
                                                                                        string(name: 'serviceName', value: String.valueOf("${params.serviceName}"))]
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