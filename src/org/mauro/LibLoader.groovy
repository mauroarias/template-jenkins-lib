package org.mauro
    
public class LibLoader {

    private static String libVersion = 'wip-0.1.0'

    public static void loadLib () {
        sh "echo 'loading lib version: ${libVersion}'"
        library identifier: "jenkins-share-lib@${libVersion}", retriever: 
            modernSCM(
                [$class: 'GitSCMSource',
                remote: 'https://github.com/mauroarias/jenkins-share-lib.git'])
        jenkinsLib.prepareLib()
    }
}