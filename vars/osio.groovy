def askForInput() {
  def proceedMessage = """Would you like to promote to the next environment?
          """
  try {
    timeout(time: approvalTimeOutMinutes, unit: 'MINUTES') {
      input id: 'Proceed', message: "\n${proceedMessage}"
    }
  } catch (err) {
    throw err
  }
}


def deploy(_environ) {
  environ = "-"  + _environ
  // Populating istag to stage project
  try {
    sh "JSON=\$(oc get -o json is/${APPLICATION_NAME} -n ${TARGET_USER}${environ});oc delete is/${APPLICATION_NAME} -n ${TARGET_USER}${environ} && echo \$JSON|oc create -n ${TARGET_USER}${environ} -f -;oc get istag -n ${TARGET_USER}${environ}"
  } catch (err) {
    error "Error running OpenShift command ${err}"
  }

  openshiftDeploy(deploymentConfig: "${APPLICATION_NAME}", namespace: '${TARGET_USER}' + environ)

  try {
    ROUTE_PREVIEW = sh (
      script: "oc get route -n ${TARGET_USER}${environ} ${APPLICATION_NAME} --template 'http://{{.spec.host}}'",
      returnStdout: true
    ).trim()
    echo _environ.capitalize() + " URL: ${ROUTE_PREVIEW}"
  } catch (err) {
    error "Error running OpenShift command ${err}"
  }
}

def getCurrentUser() {
  return sh (
    script: """oc whoami|awk -F ":" '{ gsub(/-jenkins/,"", \$3);print \$3;}'""",
    returnStdout: true
    ).trim()
}

def getCurrentRepo() {
  return sh (
    script: "git config remote.origin.url",
    returnStdout: true
    ).trim()
}

def getTemplateNameFromObject(sourceRepository, objectName) {
  return sh (
    script: "oc process -f .openshiftio/application.yaml SOURCE_REPOSITORY_URL=${sourceRepository} -o jsonpath='{.items[?(@.kind == \"${objectName}\")].metadata.name}'",
    returnStdout: true
    ).trim()
}

def call(Map parameters = [:], body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config

  node('nodejs') {
    checkout scm;


    echo "${config}"
    currentUser = getCurrentUser()
    currentGitRepo = getCurrentRepo()
    templateDC = getTemplateNameFromObject(currentGitRepo, "DeploymentConfig")
    templateDC = getTemplateNameFromObject(currentGitRepo, "BuildConfig")

    sleep(time: 10, unit: "MINUTES")

    sh """
       for i in ${currentUser} ${currentUser}-{stage,run};do
          oc process -f .openshiftio/application.yaml SOURCE_REPOSITORY_URL=${currentGitRepo} | \
            oc replace -f- -n \$i
       done

       #Remove dc from currentUser and
       oc delete dc nodejs-health-check -n ${currentUser}

       #TODO(make it smarter)
       for i in ${currentUser}-{stage,run};do
        oc delete bc nodejs-health-check -n \$i
       done
    """

  }

  // try {
  //   timestamps{
  //     timeout(time: jobTimeOutHour, unit: 'HOURS') {
  //       node('nodejs') {
  //         stage('Build') {
  //           openshiftBuild(buildConfig: "${APPLICATION_NAME}", showBuildLogs: 'true')
  //         }

  //         stage('Deploy to staging') {
  //           deploy("stage")
  //           askForInput()
  //         }

  //         stage('Deploy to production') {
  //           deploy("run")
  //         }
  //       }
  //     }
  //   }
  // } catch (err) {
  //   echo "in catch block"
  //   echo "Caught: ${err}"
  //   currentBuild.result = 'FAILURE'
  //   throw err
  // }

}
