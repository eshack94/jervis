@Grab(group='net.gleske', module='jervis', version='0.13', transitive=false)
@Grab(group='org.yaml', module='snakeyaml', version='1.18', transitive=false)

/*
   TODO:
   - Secrets support
   - non-matrix support
       - collecting artifact support
       - publishing html support
       - publishing junit test support
       - passing artifacts to the rest of the pipeline via stash
 */

import net.gleske.jervis.lang.lifecycleGenerator
import static net.gleske.jervis.lang.lifecycleGenerator.getObjectValue

/**
  Returns a list of maps which are buildable matrices in a matrix build.  This
  method takes into account that there are matrix exclusions and white lists in
  the YAML configuration.
 */
@NonCPS
List getBuildableMatrixAxes(lifecycleGenerator generator) {
    List matrix_axis_maps = generator.yaml_matrix_axes.collect { axis ->
        generator.matrixGetAxisValue(axis).split().collect {
            ["${axis}": it]
        }
    }
    if(generator.yaml_matrix_axes.size() < 2) {
        matrix_axis_maps = matrix_axis_maps[0]
    }
    else {
        //creates a list of lists which contain maps to be summed into one list of maps with every possible matrix combination
        matrix_axis_maps = matrix_axis_maps.combinations()*.sum()
    }
    //return all maps (or some maps allowed via filter)
    matrix_axis_maps.findAll {
        if(generator.matrixExcludeFilter()) {
            Binding binding = new Binding()
            it.each { k, v ->
                binding.setVariable(k, v)
            }
            //filter out the combinations (returns a boolean true or false)
            new GroovyShell(binding).evaluate(generator.matrixExcludeFilter())
        }
        else {
            //if there's no matrix exclude filter then include everything
            true
        }
    }
}

/**
  Returns a list of stashes from Jervis YAML to be stashed either serially or
  in this matrix axis for matrix builds.
 */

@NonCPS
Map getStashMap(List stashes, boolean isMatrix = false, Map matrix_axis = [:]) {
    Map stash_map = [:]
    stashes.each { s ->
        if((s instanceof Map) &&
                ('name' in s) &&
                getObjectValue(s, 'name', '') &&
                ('includes' in s) &&
                getObjectValue(s, 'includes', '') &&
                (!isMatrix || getObjectValue(s, 'matrix_axis', [:])) &&
                (!isMatrix || (getObjectValue(s, 'matrix_axis', [:]) == matrix_axis))) {
            stash_map[getObjectValue(s, 'name', '')] = [
                'name': getObjectValue(s, 'name', ''),
                'includes': getObjectValue(s, 'includes', ''),
                'excludes': getObjectValue(s, 'excludes', ''),
                'use_default_excludes': getObjectValue(s, 'use_default_excludes', 'true') == 'true',
                'allow_empty': getObjectValue(s, 'allow_empty', 'false') == 'true',
                'matrix_axis': getObjectValue(s, 'matrix_axis', [:])
                ]
        }
    }
    stash_map
}

/**
  If given a list of items, compare it to supported items for collection.
 */

@NonCPS
List getCollectItemsList(Map collect_items) {
    Set supported_collections = ['cobertura', 'junit', 'artifacts'] as Set
    Set known_items = collect_items.keySet() as Set
    (supported_collections.intersect(known_items) as List).sort()
}

/**
  Convert a matrix axis to use unfriendly names for stash comparison.
 */
@NonCPS
Map convertMatrixAxis(lifecycleGenerator generator, Map matrix_axis) {
    Map new_axis = [:]
    matrix_axis.each { k, v ->
        new_axis[k] = generator.matrix_fullName_by_friendly[v]?:v
    }
    new_axis
}

def call() {
    def generator = new lifecycleGenerator()
    String jervis_yaml
    String os_stability
    String lifecycles_json
    String toolchains_json
    String platforms_json
    String script_header
    String script_footer
    List folder_listing = []
    Map tasks = [failFast: true]
    List jervisEnvList = [
        "JERVIS_BRANCH=${env.GIT_BRANCH}"
    ]

    def global_scm = scm

    stage('Process Jervis YAML') {
        platforms_json = libraryResource 'platforms.json'
        generator.loadPlatformsString(platforms_json)
        node('master') {
            checkout global_scm
            folder_listing = sh(returnStdout: true, script: 'ls -a -1').trim().split('\n') as List
            echo "Folder list: ${folder_listing}"
            if('.jervis.yml' in folder_listing) {
                jervis_yaml = readFile '.jervis.yml'
            }
            else if('.travis.yml' in folder_listing) {
                jervis_yaml = readFile '.travis.yml'
            }
            else {
                throw new FileNotFoundException('Cannot find .jervis.yml nor .travis.yml')
            }
        }
        generator.preloadYamlString(jervis_yaml)
        os_stability = "${generator.label_os}-${generator.label_stability}"
        lifecycles_json = libraryResource "lifecycles-${os_stability}.json"
        toolchains_json = libraryResource "toolchains-${os_stability}.json"
        generator.loadLifecyclesString(lifecycles_json)
        generator.loadToolchainsString(toolchains_json)
        generator.loadYamlString(jervis_yaml)
        generator.folder_listing = folder_listing
        script_header = libraryResource "header.sh"
        script_footer = libraryResource "footer.sh"
        jervisEnvList << "JERVIS_LANG=${generator.yaml_language}"
    }

    //prepare to run
    if(generator.isMatrixBuild()) {
        //a matrix build which should be executed in parallel
        getBuildableMatrixAxes(generator).each { matrix_axis ->
            echo "Detected matrix axis: ${matrix_axis}"
            String stageIdentifier = matrix_axis.collect { k, v -> generator.matrix_fullName_by_friendly[v]?:v }.join('\n')
            String label = generator.labels
            List axisEnvList = matrix_axis.collect { k, v -> "${k}=${v}" }
            def stashes = getObjectValue(generator.jervis_yaml, 'jenkins.stash', new Object())
            Map stashMap = getStashMap((stashes instanceof List)? stashes : [stashes], true, convertMatrixAxis(generator, matrix_axis))
            tasks[stageIdentifier] = {
                node(label) {
                    stage("Checkout SCM") {
                        checkout global_scm
                    }
                    stage("Build axis ${stageIdentifier}") {
                        withEnv(axisEnvList + jervisEnvList) {
                            sh(script: [
                                script_header,
                                generator.generateAll(),
                                script_footer
                            ].join('\n').toString())
                        }
                        for(String name : stashMap.keySet()) {
                            stash allowEmpty: stashMap[name]['allow_empty'], includes: stashMap[name]['includes'], name: name, useDefaultExcludes: stashMap[name]['use_default_excludes']
                        }
                    }
                }
            }
        }
    }
    parallel(tasks)
    Map collect_items = getObjectValue(generator.jervis_yaml, 'jenkins.collect', [:])
    List collectItemsList = getCollectItemsList(collect_items)
    if(collectItemsList) {
        stage("Publish results") {
            node(generator.labels) {
                for(String name : collectItemsList) {
                    unstash name
                }
                if('artifacts' in collectItemsList) {
                    archiveArtifacts collect_items['artifacts']
                    fingerprint collect_items['artifacts']
                }
                if('cobertura' in collectItemsList) {
                    step([
                            $class: 'CoberturaPublisher',
                            autoUpdateHealth: false,
                            autoUpdateStability: false,
                            coberturaReportFile: collect_items['cobertura'],
                            failUnhealthy: false,
                            failUnstable: false,
                            maxNumberOfBuilds: 0,
                            onlyStable: false,
                            sourceEncoding: 'ASCII',
                            zoomCoverageChart: false
                    ])
                }
                if('junit' in collectItemsList) {
                    junit collect_items['junit']
                }
            }
        }
    }
}
