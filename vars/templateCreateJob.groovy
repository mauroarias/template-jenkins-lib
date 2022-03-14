import org.mauro.LibLoader

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
                        LibLoader.loadLib()
                        agentImage = templateLib.getDefaultAgent()

                        templateInfo = input message: 'choose temlate', ok: 'Next',
                        parameters: [
                            choice(choices: templateLib.getTemplates(), name: 'template', description: 'template type'),
                            choice(choices: ['gitHub', 'bitBucket'], name: 'gitDstRemote', description: 'git destination remote'),
                            string(defaultValue: '', name: 'serviceName', trim: true, description: 'project name')]
                        def templateName = templateInfo.template
                        def gitDstRemote = templateInfo.gitDstRemote
                        def serviceName = templateInfo.serviceName

                        sh "echo 'template: ${templateName}'"
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
                        gitLib.validateEnvVars("${gitDstRemote}")
                        gitLib.createProjectIfNotExitsIfAppl("${gitDstRemote}", "${projectName}")
                        if (gitLib.isRepositoryExits("${gitDstRemote}", "${serviceName}")) {
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
                        branch = templateLib.getBranch("${templateName}")
                        template =  templateLib.getTemplateType("${templateName}")
                        agentImage =  templateLib.getAgentByTemplate("${templateName}")
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
                        sh "echo ${serviceName}"
                        sh "rm -rf ${serviceName}"
                        sh "./${template}/prepare.sh ${serviceName}"
                        sh "mv ${template} ${serviceName}"
                        sh "rm ${serviceName}/prepare.sh"
                        jenkinsLib.stash('template', "${serviceName}/**/*", "${serviceName}/.git", false)
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
                        dir("${serviceName}") {
                            gitLib.createRepo("${gitDstRemote}", "${serviceName}", "${projectName}")
                            jenkinsLib.createJenkinsPipelineFileWithLib("${templateLib.getCiPipeline()}", "${templateLib.getCiVersion()}")
                            gitLib.initRepo("${gitDstRemote}", "${GIT_EMAIL}", "${GIT_USER}", "${serviceName}", 'origin')
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
                        build job: 'create-ci-cd-jobs', wait: true, parameters: [string(name: 'gitDstRemote', value: String.valueOf("${gitDstRemote}")),
                                                                                        string(name: 'projectName', value: String.valueOf("${projectName}")),
                                                                                        string(name: 'serviceName', value: String.valueOf("${tserviceName}"))]
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

def loadLib () {
    def libVersion = 'wip-0.1.0'
    sh "echo 'loading lib version: ${libVersion}'"
    library identifier: "jenkins-share-lib@${libVersion}", retriever: 
        modernSCM(
            [$class: 'GitSCMSource',
            remote: 'https://github.com/mauroarias/jenkins-share-lib.git'])
}
