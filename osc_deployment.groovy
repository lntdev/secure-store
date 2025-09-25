// Scripted pipeline: build Docker image from /var/lib/jenkins/secure-store/app,
// push to ECR or Docker Hub, then deploy with Terraform from /var/lib/jenkins/secure-store/infra/aws.

node('master') {

  // -------- Parameters shown in the job UI --------
  properties([
    parameters([
      choice(name: 'CLOUD_PROVIDER', choices: ['aws'], description: 'Cloud provider'),
      choice(name: 'ACTION', choices: ['apply', 'destroy'], description: 'Terraform action'),
      string(name: 'AWS_REGION', defaultValue: 'ap-south-1', description: 'AWS region'),
      string(name: 'APP_NAME', defaultValue: 'secure-store', description: 'Application name'),
      string(name: 'VERSION', defaultValue: '1.0.0', description: 'Image tag/version'),
      choice(name: 'REGISTRY', choices: ['ecr', 'dockerhub'], description: 'Container registry'),
      string(name: 'DOCKERHUB_USER', defaultValue: 'manumankale', description: 'Docker Hub username (if REGISTRY=dockerhub)'),
      password(name: 'SECRET_KEY', defaultValue: 'change-me', description: 'Flask SECRET_KEY (use Jenkins creds in prod)')
    ])
  ])

  // -------- Fixed paths on your Jenkins box --------
  def BASE_DIR       = '/var/lib/jenkins/secure-store'
  def DOCKER_CONTEXT = "${BASE_DIR}/app"
  def TF_WORKDIR     = "${BASE_DIR}/infra/aws"
  def IMAGE_META_DIR = BASE_DIR
  def IMAGE_URI_FILE = "${IMAGE_META_DIR}/IMAGE_URI.env"
  def IMAGE_ACCT_FILE= "${IMAGE_META_DIR}/IMAGE_URI.env.acct"

  def IMAGE_URI = ''
  def ACCOUNT_ID = ''

  try {

    stage('Verify Layout') {
      sh """
        set -euxo pipefail
        test -d ${DOCKER_CONTEXT}
        test -d ${TF_WORKDIR}
        ls -la ${BASE_DIR}
      """
    }

    // If your job checks out from SCM into workspace, you can sync to BASE_DIR here (optional).
    // stage('Sync from SCM to BASE_DIR') { sh "rsync -a --delete ./ ${BASE_DIR}/" }

    stage('Docker Build') {
      dir(DOCKER_CONTEXT) {
        sh """
          set -euxo pipefail
          docker build --no-cache -t ${params.APP_NAME}:build-${params.VERSION} .
        """
      }
    }

    stage('Login, Tag & Push Image') {
      if (params.REGISTRY == 'ecr') {
        withCredentials([usernamePassword(credentialsId: 'aws-creds', usernameVariable: 'AWS_KEY', passwordVariable: 'AWS_SECRET')]) {
          withEnv(["AWS_ACCESS_KEY_ID=${AWS_KEY}", "AWS_SECRET_ACCESS_KEY=${AWS_SECRET}", "AWS_REGION=${params.AWS_REGION}"]) {
            sh """
              set -euxo pipefail
              ACCOUNT_ID=\$(aws sts get-caller-identity --query Account --output text)
              echo "\$ACCOUNT_ID" > ${IMAGE_ACCT_FILE}
              REPO="\$ACCOUNT_ID.dkr.ecr.${params.AWS_REGION}.amazonaws.com/${params.APP_NAME}"
              aws ecr describe-repositories --repository-names ${params.APP_NAME} >/dev/null 2>&1 || \
                aws ecr create-repository --repository-name ${params.APP_NAME} >/dev/null
              aws ecr get-login-password --region ${params.AWS_REGION} | docker login --username AWS --password-stdin "\$ACCOUNT_ID.dkr.ecr.${params.AWS_REGION}.amazonaws.com"
              docker tag ${params.APP_NAME}:build-${params.VERSION} "\$REPO:${params.VERSION}"
              docker tag ${params.APP_NAME}:build-${params.VERSION} "\$REPO:latest"
              docker push "\$REPO:${params.VERSION}"
              docker push "\$REPO:latest"
              echo "IMAGE_URI=\$REPO:${params.VERSION}" > ${IMAGE_URI_FILE}
            """
            ACCOUNT_ID = readFile(IMAGE_ACCT_FILE).trim()
            IMAGE_URI  = readFile(IMAGE_URI_FILE).trim().split('=')[1]
          }
        }
      } else {
        withCredentials([usernamePassword(credentialsId: 'docker-hub', usernameVariable: 'DHU', passwordVariable: 'DHP')]) {
          sh """
            set -euxo pipefail
            echo "${DHP}" | docker login -u "${DHU}" --password-stdin
            docker tag ${params.APP_NAME}:build-${params.VERSION} ${params.DOCKERHUB_USER}/${params.APP_NAME}:${params.VERSION}
            docker tag ${params.APP_NAME}:build-${params.VERSION} ${params.DOCKERHUB_USER}/${params.APP_NAME}:latest
            docker push ${params.DOCKERHUB_USER}/${params.APP_NAME}:${params.VERSION}
            docker push ${params.DOCKERHUB_USER}/${params.APP_NAME}:latest
            echo "IMAGE_URI=${params.DOCKERHUB_USER}/${params.APP_NAME}:${params.VERSION}" > ${IMAGE_URI_FILE}
          """
          IMAGE_URI = readFile(IMAGE_URI_FILE).trim().split('=')[1]
        }
      }
      echo "Published image: ${IMAGE_URI}"
    }

    stage('Terraform Init & Plan') {
      dir(TF_WORKDIR) {
        withCredentials([usernamePassword(credentialsId: 'aws-creds', usernameVariable: 'AWS_KEY', passwordVariable: 'AWS_SECRET')]) {
          withEnv(["AWS_ACCESS_KEY_ID=${AWS_KEY}", "AWS_SECRET_ACCESS_KEY=${AWS_SECRET}", "AWS_REGION=${params.AWS_REGION}"]) {
            sh """
              set -euxo pipefail
              terraform init -upgrade
              terraform plan \
                -var="container_image=${IMAGE_URI}" \
                -var="secret_key=${params.SECRET_KEY}" \
                -var="app_name=${params.APP_NAME}" \
                -var="aws_region=${params.AWS_REGION}"
            """
          }
        }
      }
    }

    stage('Terraform Apply/Destroy') {
      dir(TF_WORKDIR) {
        withCredentials([usernamePassword(credentialsId: 'aws-creds', usernameVariable: 'AWS_KEY', passwordVariable: 'AWS_SECRET')]) {
          withEnv(["AWS_ACCESS_KEY_ID=${AWS_KEY}", "AWS_SECRET_ACCESS_KEY=${AWS_SECRET}", "AWS_REGION=${params.AWS_REGION}"]) {
            if (params.ACTION == 'apply') {
              sh """
                set -euxo pipefail
                terraform apply -auto-approve \
                  -var="container_image=${IMAGE_URI}" \
                  -var="secret_key=${params.SECRET_KEY}" \
                  -var="app_name=${params.APP_NAME}" \
                  -var="aws_region=${params.AWS_REGION}"
              """
            } else {
              sh """
                set -euxo pipefail
                terraform destroy -auto-approve
              """
            }
          }
        }
      }
    }

  } finally {
    // Keep a record of which image was deployed
    archiveArtifacts artifacts: "${IMAGE_URI_FILE}, ${IMAGE_ACCT_FILE}", allowEmptyArchive: true
    sh 'docker image prune -f || true'
  }
}
