import io
import os
from pathlib import Path
import re
import tarfile
import unittest
import xml.etree.ElementTree as ET


PROJECT = Path(__file__).resolve().parents[1]
SERVER = PROJECT / 'hkex-cmdb-sync-server'
NS = {'a': 'http://maven.apache.org/plugins/maven-assembly-plugin/assembly/1.1.3',
      'p': 'http://maven.apache.org/POM/4.0.0'}
SERVICE = 'hkexCmdbSync'


class ProductDefinitionTests(unittest.TestCase):
    def test_maven_produces_service_before_product(self):
        pom = ET.parse(SERVER / 'pom.xml')
        plugin = next(p for p in pom.findall('.//p:plugin', NS)
                      if p.findtext('p:artifactId', namespaces=NS) == 'maven-assembly-plugin')
        executions = plugin.findall('p:executions/p:execution', NS)
        self.assertEqual([e.findtext('p:id', namespaces=NS) for e in executions],
                         ['make-service-package', 'make-product-package'])
        for execution in executions:
            self.assertEqual(execution.findtext('p:phase', namespaces=NS), 'package')
        self.assertEqual(executions[1].findtext('p:configuration/p:finalName', namespaces=NS),
                         '${omp.product.name}-${project.version}-${build.time}-install')
        self.assertEqual(plugin.findtext('p:configuration/p:tarLongFileMode', namespaces=NS), 'gnu')

    def test_product_has_gcp_three_level_layout(self):
        assembly = ET.parse(SERVER / 'src/main/assembly/omp-product.xml')
        self.assertEqual(assembly.findtext('a:baseDirectory', namespaces=NS), '${omp.product.name}')
        files = assembly.findall('a:files/a:file', NS)
        product = next(f for f in files if f.findtext('a:source', namespaces=NS).endswith('omp-product.yaml'))
        self.assertEqual(product.findtext('a:destName', namespaces=NS), '${omp.product.name}.yaml')
        self.assertEqual(product.findtext('a:filtered', namespaces=NS), 'true')
        service = next(f for f in files if f.findtext('a:source', namespaces=NS).endswith('.tar.gz'))
        self.assertEqual(service.findtext('a:source', namespaces=NS),
                         '${project.build.directory}/${final-tar-name}.tar.gz')
        definition = assembly.find('a:fileSets/a:fileSet', NS)
        self.assertEqual(definition.findtext('a:outputDirectory', namespaces=NS), '${omp.product.name}')
        self.assertEqual(definition.findtext('a:includes/a:include', namespaces=NS), SERVICE + '.yaml')

    def test_yaml_versions_and_ownership_use_maven_filtering(self):
        product = (SERVER / 'conf/omp-product.yaml').read_text()
        service = (SERVER / ('conf/' + SERVICE + '.yaml')).read_text()
        self.assertIn('kind: product\n', product)
        self.assertIn('name: ${omp.product.name}\n', product)
        self.assertIn('version: ${project.version}\n', product)
        self.assertIn('dependencies:\n', product)
        self.assertIn('labels:\n', product)
        self.assertIn('  - name: hkexCmdbSync\n', product)
        self.assertIn('version: ${project.version}\n', service)
        self.assertIn('  name: ${omp.product.name}\n', service)
        self.assertNotRegex(service, r'@[A-Z_]+@')

    def test_service_assembly_includes_omp_lifecycle(self):
        assembly = ET.parse(SERVER / 'src/main/assembly/assembly.xml')
        sets = assembly.findall('a:fileSets/a:fileSet', NS)
        for required in (SERVICE + '.yaml', 'service.properties'):
            self.assertTrue(any(required in [i.text for i in s.findall('a:includes/a:include', NS)]
                                and s.findtext('a:filtered', namespaces=NS) == 'true' for s in sets), required)
        scripts = next(s for s in sets if s.findtext('a:outputDirectory', namespaces=NS) == 'scripts')
        self.assertIn('**/__pycache__/**', [e.text for e in scripts.findall('a:excludes/a:exclude', NS)])

    def test_service_assembly_includes_current_deployment_guide(self):
        assembly = ET.parse(SERVER / 'src/main/assembly/assembly.xml')
        docs = next(s for s in assembly.findall('a:fileSets/a:fileSet', NS)
                    if s.findtext('a:outputDirectory', namespaces=NS) == 'docs')
        self.assertIn('deployment.md', [e.text for e in docs.findall('a:includes/a:include', NS)])


@unittest.skipUnless(os.environ.get('OMP_CHECK_ARTIFACTS') == '1', '需先完成 Maven package')
class ProductArchiveTests(unittest.TestCase):
    def test_product_and_nested_service_are_consistent(self):
        packages = list((SERVER / 'target').glob(SERVICE + '-*-install.tar.gz'))
        self.assertTrue(packages, 'Maven 未生成产品包')
        package = max(packages, key=lambda p: p.stat().st_mtime)
        self.assertRegex(package.name, r'^hkexCmdbSync-\d+(?:\.\d+)+-\d{14}-install\.tar\.gz$')
        with tarfile.open(package) as outer:
            files = [m.name for m in outer.getmembers() if m.isfile()]
            product_yaml = SERVICE + '/' + SERVICE + '.yaml'
            service_yaml = SERVICE + '/' + SERVICE + '/' + SERVICE + '.yaml'
            inner_names = [n for n in files if n.endswith('.tar.gz')]
            self.assertEqual(len(inner_names), 1)
            self.assertEqual(set(files), {product_yaml, service_yaml, inner_names[0]})
            name = Path(inner_names[0]).name
            match = re.fullmatch(r'hkexCmdbSync-(\d+(?:\.\d+)+)-(\d{14})-([0-9a-f]+)\.tar\.gz', name)
            self.assertIsNotNone(match)
            product = outer.extractfile(product_yaml).read().decode()
            service = outer.extractfile(service_yaml).read()
            self.assertIn('kind: product\n', product)
            self.assertIn('version: ' + match[1] + '\n', product)
            self.assertIn('version: ' + match[1] + '\n', service.decode())
            self.assertNotRegex(product + service.decode(), r'\$\{|@[A-Z_]+@')
            archive_bytes = outer.extractfile(inner_names[0]).read()
            self.assertEqual(archive_bytes, (SERVER / 'target' / name).read_bytes())
            with tarfile.open(fileobj=io.BytesIO(archive_bytes)) as inner:
                names = inner.getnames()
                self.assertEqual(inner.extractfile(SERVICE + '/' + SERVICE + '.yaml').read(), service)
                properties = inner.extractfile(SERVICE + '/service.properties').read().decode()
                self.assertIn('service-version=' + match[1] + '\n', properties)
                self.assertIn('product-name=' + SERVICE + '\n', properties)
                for required in ('scripts/install.py', 'scripts/upgrade.py', 'scripts/rollback.py',
                                 'bin/hkexCmdbSync', 'bin/publishCmdbConfig', 'bin/java17.sh',
                                 'conf/application.properties', 'docs/deployment.md'):
                    self.assertIn(SERVICE + '/' + required, names)
                monitoring = inner.extractfile(SERVICE + '/conf/application.properties').read().decode()
                for setting in ('management.endpoints.web.base-path=/',
                                'management.endpoints.web.path-mapping.prometheus=metrics',
                                'management.endpoints.web.exposure.include=health,prometheus',
                                'management.endpoint.health.show-details=never',
                                'management.endpoint.health.show-components=never'):
                    self.assertIn(setting, monitoring)
                self.assertTrue(any('/lib/spring-boot-actuator-' in n for n in names))
                self.assertTrue(any('/lib/micrometer-registry-prometheus-' in n for n in names))
                self.assertTrue(any('/lib/hkex-cmdb-sync-server-' in n for n in names))
                for n in names:
                    self.assertNotRegex(n, r'__pycache__|\.pyc$|\.DS_Store|nacos\.params|omp\.env|application-(dev|prod|local)\.(yml|properties)$')


if __name__ == '__main__':
    unittest.main()
