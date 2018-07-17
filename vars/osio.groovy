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


def deployEnvironment(_environ, user, is, dc, route) {
  environ = "-"  + _environ

  try {
    sh "oc tag -n ${user}${environ} --alias=true ${user}/${is}:latest ${is}:latest"
  } catch (err) {
    error "Error running OpenShift command ${err}"
  }

  openshiftDeploy(deploymentConfig: "${dc}", namespace: "${user}" + environ)

  try {
    ROUTE_PREVIEW = sh (
      script: "oc get route -n ${user}${environ} ${route} --template 'http://{{.spec.host}}'",
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

def main(label) {
  node(label) {
    checkout scm;

    currentUser = getCurrentUser()
    currentGitRepo = getCurrentRepo()
    // TODO: What about if we have multiple DC/IS then we would have to humm improve this
    templateDC = getTemplateNameFromObject(currentGitRepo, "DeploymentConfig")
    templateBC = getTemplateNameFromObject(currentGitRepo, "BuildConfig")
    templateISDest = getTemplateNameFromObject(currentGitRepo, "ImageStream").minus("runtime").trim()
    templateRoute = getTemplateNameFromObject(currentGitRepo, "Route")

    stage('Creating configuration') {
      sh """
       for i in ${currentUser} ${currentUser}-{stage,run};do
          oc process -f .openshiftio/application.yaml SOURCE_REPOSITORY_URL=${currentGitRepo} | \
            oc apply -f- -n \$i
       done

       #Remove dc from currentUser
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
      deployEnvironment("stage", "${currentUser}", "${templateISDest}", "${templateDC}", "${templateRoute}")
      askForInput()
    }

    stage('Deploy to Prod') {
      deployEnvironment("run", "${currentUser}", "${templateISDest}", "${templateDC}", "${templateRoute}")
    }
  }
}

def call(Map parameters = [:], body) {
  //TODO: parameters
  def jobTimeOutHour = 1
  def defaultLabel = 'maven'

  return

  def label = parameters.get('label', defaultLabel)

  def stages = parameters.get('stage')
  println("Label: ${defaultLabel}")
  println("Label: ${label}")
  println("Parameters: ${stages}")

  try {
    timestamps{
      timeout(time: jobTimeOutHour, unit: 'HOURS') {
        main(label)
      }
    }
  } catch (err) {
    echo "in catch block"
    echo "Caught: ${err}"
    currentBuild.result = 'FAILURE'
    throw err
  }
}
