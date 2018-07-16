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

}

def call(Map parameters = [:], body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config

  node('nodejs') {
    checkout scm;


    sleep(time: 10, unit: "MINUTES")

    //sh "oc get project -o go-template='{{range .items}}{{.metadata.name}}{{\"\n\"}}{{end}}'|sed -n '/-jenkins/{ s/-jenkins//;p;}')""

    print "DEBUG: parameter foo = ${config}"

    sh 'oc get project -o json|jq ".items[] | .metadata.name"'
    // sh """
    //    for i in ${currentProject} ${currentProject}}-{stage,run};do
    //       oc process -f .openshiftio/application.yaml SOURCE_REPOSITORY_URL=https://github.com/chmouel/nodejs-health-check | \
    //         oc apply -f- -n cboudjna-preview"

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
