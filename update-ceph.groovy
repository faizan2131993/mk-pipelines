/**
 * Update packages
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 */

pepperEnv = "pepperEnv"
def salt = new com.mirantis.mk.Salt()
def ceph = new com.mirantis.mk.Ceph()
def python = new com.mirantis.mk.Python()
def packages
def command
def commandKwargs
def selMinions = []
def flags = CLUSTER_FLAGS ? CLUSTER_FLAGS.tokenize(',') : []

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {
            def targets = ["common": "ceph-common", "osd": "ceph-osd", "mon": "ceph-mon",
                           "mgr"   : "ceph-mgr", "radosgw": "radosgw"]

            stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            stage('Apply package upgrades on all nodes') {

                targets.each { key, value ->
                    // try {
                    command = "pkg.install"
                    packages = value
                    commandKwargs = ['only_upgrade': 'true', 'force_yes': 'true']
                    target = "I@ceph:${key}"
                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': target, 'type': 'compound'], command, true, packages, commandKwargs)
                    salt.printSaltCommandResult(out)
                }
            }

            stage("Restart MONs and RGWs") {
                selMinions = salt.getMinions(pepperEnv, "I@ceph:mon")
                for (tgt in selMinions) {
                    // runSaltProcessStep 'service.restart' don't work for this services
                    salt.cmdRun(pepperEnv, tgt, "systemctl restart ceph-mon.target")
                    ceph.waitForHealthy(pepperEnv, tgt, flags)
                }
                selMinions = salt.getMinions(pepperEnv, "I@ceph:mgr")
                for (tgt in selMinions) {
                    // runSaltProcessStep 'service.restart' don't work for this services
                    salt.cmdRun(pepperEnv, tgt, "systemctl restart ceph-mgr.target")
                    ceph.waitForHealthy(pepperEnv, tgt, flags)
                }
                selMinions = salt.getMinions(pepperEnv, "I@ceph:radosgw")
                for (tgt in selMinions) {
                    salt.cmdRun(pepperEnv, tgt, "systemctl restart ceph-radosgw.target")
                    ceph.waitForHealthy(pepperEnv, tgt, flags)
                }
            }

            stage('Restart OSDs') {
                def device_grain_name =  salt.getPillar(pepperEnv,"I@ceph:osd","ceph:osd:lvm_enabled")['return'].first().containsValue(true) ? "ceph_volume" : "ceph_disk"
                selMinions = salt.getMinions(pepperEnv, "I@ceph:osd")
                for (tgt in selMinions) {
                    salt.runSaltProcessStep(pepperEnv, tgt, 'saltutil.sync_grains', [], null, true, 5)
                    def ceph_disks = salt.getGrain(pepperEnv, tgt, 'ceph')['return'][0].values()[0].values()[0][device_grain_name]

                    def osd_ids = []
                    for (i in ceph_disks) {
                        def osd_id = i.getKey().toString()
                        osd_ids.add('osd.' + osd_id)
                    }

                    salt.cmdRun(pepperEnv, tgt, 'ceph osd set noout')
                    flags = 'noout' in flags ? flags : flags + ['noout']

                    for (i in osd_ids) {
                        salt.runSaltProcessStep(pepperEnv, tgt, 'service.restart', ['ceph-osd@' + i.replaceAll('osd.', '')], null, true)
                        // wait for healthy cluster
                        ceph.waitForHealthy(pepperEnv, tgt, flags, 0, 100)
                    }

                    salt.cmdRun(pepperEnv, tgt, 'ceph osd unset noout')
                }
            }


        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}