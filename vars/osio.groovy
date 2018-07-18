import groovy.json.JsonSlurperClassic

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
    script: "oc whoami|sed 's/.*:\\(.*\\)-jenkins:.*/\\1/'",
    returnStdout: true
    ).trim()
}

def getCurrentRepo() {
  return sh (
    script: "git config remote.origin.url",
    returnStdout: true
    ).trim()
}

def getJsonFromProcessedTemplate(sourceRepository) {
  def output = sh (
    script: "oc process -f .openshiftio/application.yaml SOURCE_REPOSITORY_URL=${sourceRepository} -o json",
    returnStdout: true
  ).trim()
  return new groovy.json.JsonSlurperClassic().parseText(output.trim())
}

def getNameFromTemplate(json, type) {
  def r = json.items.findResults { i ->
    i.kind == type ?
      i.metadata.name :
      null
  }

  // For ImageStream we need to filter out the runtime stuff
  if (type == "ImageStream") {
    r = r.findResults { i ->
      !i.startsWith("runtime") ?
      i :
      null
    }
  }

  if (r.size() == 0) {
    throw new Exception("We didn't find any ${type}")
  }
  if (r.size() > 1) {
    throw new Exception("There should be only one ${type} we have: ${r}")
  }
  return r[0]
}


def main(params) {
  node(params.get('nodejs')) {
    checkout scm;

    currentUser = getCurrentUser()
    currentGitRepo = getCurrentRepo()

    json = getJsonFromProcessedTemplate(currentGitRepo)
    templateDC = getNameFromTemplate(json, "DeploymentConfig")
    templateBC = getNameFromTemplate(json, "BuildConfig")
    templateISDest = getNameFromTemplate(json, "ImageStream")
    templateRoute = getNameFromTemplate(json, "Route")
    println "Stages: " + params.get('stage')
    return
    stage('Processing Template') {
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
