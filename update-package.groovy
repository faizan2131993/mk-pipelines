/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *   TARGET_SERVERS             Salt compound target to match nodes to be updated [*, G@osfamily:debian].
 *   TARGET_PACKAGES            Space delimited list of packages to be updates [package1=version package2=version], empty string means all updating all packages to the latest version.
 *   BATCH_SIZE                 Use batching for large amount of target nodes
 *
**/

pepperEnv = "pepperEnv"
salt = new com.mirantis.mk.Salt()
common = new com.mirantis.mk.Common()

def batch_size = ''
if (common.validInputParam('BATCH_SIZE')) {
    batch_size = "${BATCH_SIZE}"
}

def installSaltStack(target, pkgs, batch, masterUpdate = false){
    salt.cmdRun(pepperEnv, "I@salt:master", "salt -C '${target}' --async pkg.install force_yes=True pkgs='$pkgs'")
    def minions_reachable = target
    if (masterUpdate) {
        // in case of update Salt Master packages - check all minions are good
        minions_reachable = '*'
    }
    salt.checkTargetMinionsReady(['saltId': pepperEnv, 'target': target, 'target_reachable': minions_reachable, 'batch': batch])
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {
            def python = new com.mirantis.mk.Python()
            def command = 'pkg.upgrade'
            def commandKwargs = null
            def packages = null
            stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            def targetLiveAll = ''
            stage('Get target servers') {
                def minions = salt.getMinions(pepperEnv, TARGET_SERVERS)
                if (minions.isEmpty()) {
                    throw new Exception("No minion was targeted")
                }
                targetLiveAll = minions.join(' or ')
                common.infoMsg("Found nodes: ${targetLiveAll}")
            }

            stage("List package upgrades") {
                common.infoMsg("Listing all the packages that have a new update available on nodes: ${targetLiveAll}")
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'pkg.list_upgrades', [], batch_size, true)
                if (TARGET_PACKAGES != '' && TARGET_PACKAGES != '*') {
                    if (ALLOW_DEPENDENCY_UPDATE.toBoolean()) {
                        common.warningMsg("Note that the \"${TARGET_PACKAGES}\" and it new dependencies would be installed from the above list of available updates on the ${targetLiveAll}")
                    } else {
                        common.warningMsg("Note that only the \"${TARGET_PACKAGES}\" would be installed from the above list of available updates on the ${targetLiveAll}")
                        commandKwargs = ['only_upgrade': 'true']
                    }
                    command = "pkg.install"
                    packages = TARGET_PACKAGES.tokenize(' ')
                }
            }

            stage('Confirm package upgrades on all nodes') {
                timeout(time: 2, unit: 'HOURS') {
                   input message: "Approve package upgrades on ${targetLiveAll} nodes?"
                }
            }

            stage('Apply package upgrades on all nodes') {
                if (packages == null || packages.contains("salt-master") || packages.contains("salt-common") || packages.contains("salt-minion") || packages.contains("salt-api")) {
                    common.warningMsg('Detected update for some Salt package (master or minion). Updating it first.')
                    def saltTargets = (targetLiveAll.split(' or ').collect { it as String })
                    for (int i = 0; i < saltTargets.size(); i++ ) {
                        common.retry(10, 5) {
                            if (salt.getMinions(pepperEnv, "I@salt:master and ${saltTargets[i]}")) {
                                installSaltStack("I@salt:master and ${saltTargets[i]}", '["salt-master", "salt-common", "salt-api", "salt-minion"]', null, true)
                            } else if (salt.getMinions(pepperEnv, "I@salt:minion and not I@salt:master and ${saltTargets[i]}")) {
                                installSaltStack("I@salt:minion and not I@salt:master and ${saltTargets[i]}", '["salt-minion"]', batch_size)
                            } else {
                                error("Minion ${saltTargets[i]} is not reachable!")
                            }
                        }
                    }
                }
                common.infoMsg('Starting package upgrades...')
                out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, batch_size, packages, commandKwargs)
                salt.printSaltCommandResult(out)
                for(value in out.get("return")[0].values()){
                    if (value.containsKey('result') && value.result == false) {
                        throw new Exception("The package upgrade on nodes has failed. Please check the Salt run result above for more information.")
                    }
                }
            }

            common.warningMsg("Pipeline has finished successfully, but please, check if any packages have been kept back.")

        } catch (Throwable e) {
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}
