def askForInput() {
  //TODO: parameters
  def approvalTimeOutMinutes = 30;
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


def deployEnvironment(_environ, target_user, dc, route) {
  environ = "-"  + _environ

  try {
    sh "oc tag -n ${target_user}${environ} --alias=true ${target_user}/runtime:latest runtime:latest"
  } catch (err) {
    error "Error running OpenShift command ${err}"
  }

  openshiftDeploy(deploymentConfig: "${dc}", namespace: "${target_user}" + environ)

  try {
    ROUTE_PREVIEW = sh (
      script: "oc get route -n ${target_user}${environ} ${route} --template 'http://{{.spec.host}}'",
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

def main() {
  node('nodejs') {
    checkout scm;

    currentUser = getCurrentUser()
    currentGitRepo = getCurrentRepo()
    templateDC = getTemplateNameFromObject(currentGitRepo, "DeploymentConfig")
    templateBC = getTemplateNameFromObject(currentGitRepo, "BuildConfig")
    templateRoute = getTemplateNameFromObject(currentGitRepo, "Route")

    stage('Creating configuration') {
      sh """
       for i in ${currentUser} ${currentUser}-{stage,run};do
          oc process -f .openshiftio/application.yaml SOURCE_REPOSITORY_URL=${currentGitRepo} | \
            oc apply -f- -n \$i || true
       done

       #Remove dc from currentUser and
       oc delete dc ${templateDC} -n ${currentUser} || true

       #TODO(make it smarter)
       for i in ${currentUser}-{stage,run};do
        oc delete bc ${templateBC} -n \$i || true
       done
    """
    }

    stage('Building application') {
      openshiftBuild(buildConfig: "${templateBC}", showBuildLogs: 'true')
    }


    stage('Deploy to staging') {
      deployEnvironment("stage", "${currentUser}", "${templateDC}", "${templateRoute}")
      askForInput()
    }
  }
}

def call(Map parameters = [:], body) {
  //TODO: parameters
  def jobTimeOutHour = 1

  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config


  try {
    timestamps{
      timeout(time: jobTimeOutHour, unit: 'HOURS') {
        main()
      }
    }
  } catch (err) {
    echo "in catch block"
    echo "Caught: ${err}"
    currentBuild.result = 'FAILURE'
    throw err
  }
}
