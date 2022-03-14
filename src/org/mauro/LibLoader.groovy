package org.mauro
    
class LibLoader implements Serializable {

    def private static String libVersion = 'wip-0.1.0'

    def public static void loadLib () {
        sh "echo 'loading lib version: ${libVersion}'"
        library identifier: "jenkins-share-lib@${libVersion}", retriever: 
            modernSCM(
                [$class: 'GitSCMSource',
                remote: 'https://github.com/mauroarias/jenkins-share-lib.git'])
    }
}