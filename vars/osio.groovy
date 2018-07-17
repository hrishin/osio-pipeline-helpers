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
    script: """
oc process -f .openshiftio/application.yaml SOURCE_REPOSITORY_URL=${sourceRepository} -o json | python -c 'import sys, json; blob = json.load(sys.stdin);print(" ".join([x["metadata"]["name"] for x in blob["items"] if x["kind"] == "${objectName}" and not x["metadata"]["name"].startswith("runtime")]))'
""",
    returnStdout: true
    ).trim()
}

def main(params) {
  node {
    checkout scm;

    currentUser = getCurrentUser()
    currentGitRepo = getCurrentRepo()
    // TODO: What about if we have multiple DC/IS then we would have to humm improve this
    templateDC = getTemplateNameFromObject(currentGitRepo, "DeploymentConfig")
    templateBC = getTemplateNameFromObject(currentGitRepo, "BuildConfig")
    templateISDest = getTemplateNameFromObject(currentGitRepo, "ImageStream")
    templateRoute = getTemplateNameFromObject(currentGitRepo, "Route")

    stage('Creating configuration') {
      sh """
       set -u
       set -e

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

def call(body) {
  def pipelineParams= [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = pipelineParams
  body()

  //TODO: parameters
  def jobTimeOutHour = 1
  try {
    timeout(time: jobTimeOutHour, unit: 'HOURS') {
      main(pipelineParams)
    }
  } catch (err) {
    echo "in catch block"
    echo "Caught: ${err}"
    currentBuild.result = 'FAILURE'
    throw err
  }
}
