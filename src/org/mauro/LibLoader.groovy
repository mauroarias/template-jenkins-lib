package org.mauro

def libVersion = 'wip-0.1.0'
    
def loadLib () {
    sh "echo 'loading lib version: ${libVersion}'"
    library identifier: "jenkins-share-lib@${libVersion}", retriever: 
        modernSCM(
            [$class: 'GitSCMSource',
            remote: 'https://github.com/mauroarias/jenkins-share-lib.git'])
}

return this