// Integration checks of the release archives, after scripts/package.mjs.
import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
const id="io.github.lithe-idea.maven-build";
const version=JSON.parse(readFileSync(`${id}/plugin.json`)).version;
const read=(archive,path)=>execFileSync('unzip',['-p',archive,`${id}/${path}`],{timeout:10000,maxBuffer:16*1024*1024});
const digest=path=>createHash('sha256').update(readFileSync(path)).digest('hex');
const windows=resolve(`dist/maven-build-${version}-windows.zip`);
test('Windows ARM64 and x64 use the verified portable archive',()=>{
 const manifest=JSON.parse(read(windows,'plugin.json'));
 assert.equal(manifest.version,version);
 assert.equal(digest(windows),digest(`dist/maven-build-${version}-darwin.zip`));
 assert.ok(read(windows,'lib/lithe-maven-extension.jar').length>0);
 const candidates=manifest.buildSystems[0].invocation.executables.filter(entry=>entry.platforms?.includes('windows'));
 assert.deepEqual(candidates.map(entry=>entry.path),['mvnw.cmd','bin/mvn.cmd'],'Windows resolves the wrapper and MAVEN_HOME launchers');
});
test('every archive entry can be extracted on Windows',()=>{
 const entries=execFileSync('unzip',['-Z1',windows],{encoding:'utf8',timeout:10000}).trim().split('\n');
 const seen=new Set();
 for(const entry of entries){
  for(const part of entry.replace(/\/$/,'').split('/')){
   assert.doesNotMatch(part,/[<>:"\\|?*\x00-\x1f]|[. ]$/,`${entry} has a name Windows refuses`);
   assert.doesNotMatch(part,/^(con|prn|aux|nul|com\d|lpt\d)(\.|$)/i,`${entry} uses a reserved Windows name`);
  }
  const folded=entry.toLowerCase();
  assert.ok(!seen.has(folded),`${entry} differs from another entry only in case`);
  seen.add(folded);
 }
});
