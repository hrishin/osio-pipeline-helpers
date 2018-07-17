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


def deployEnvironment(_environ, application_name, imagestream) {
  environ = "-"  + _environ

  try {
    sh "oc tag -n ${target_user}${environ} --alias=true ${target_user}/${imagestream}:latest ${imagestream}:latest"
  } catch (err) {
    error "Error running OpenShift command ${err}"
  }

  openshiftDeploy(deploymentConfig: "${application_name}", namespace: "${target_user}" + environ)

  try {
    ROUTE_PREVIEW = sh (
      script: "oc get route -n ${target_user}${environ} ${application_name} --template 'http://{{.spec.host}}'",
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

    currentUser = getCurrentUser()
    currentGitRepo = getCurrentRepo()
    templateDC = getTemplateNameFromObject(currentGitRepo, "DeploymentConfig")
    templateBC = getTemplateNameFromObject(currentGitRepo, "BuildConfig")

    stage('Creating configuration') {
      sh """
       for i in ${currentUser} ${currentUser}-{stage,run};do
          oc process -f .openshiftio/application.yaml SOURCE_REPOSITORY_URL=${currentGitRepo} | \
            oc apply -f- -n \$i
       done

       #Remove dc from currentUser and
       oc delete dc ${templateDC} -n ${currentUser}

       #TODO(make it smarter)
       for i in ${currentUser}-{stage,run};do
        oc delete bc ${templateBC} -n \$i
       done
    """
    }

    stage('Building application') {
      openshiftBuild(buildConfig: "${templateBC}", showBuildLogs: 'true')
    }


    stage('Deploy to staging') {
      deployEnvironment("stage", "${currentUser}")
      //askForInput()
    }
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
