// Integration checks of the release archives, after scripts/package.mjs.
import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync,mkdtempSync,rmSync,statSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {createHash} from 'node:crypto';
const id="io.github.lithe-idea.maven-build";
const version=JSON.parse(readFileSync(`${id}/plugin.json`)).version;
const read=(archive,path)=>execFileSync('unzip',['-p',archive,`${id}/${path}`],{timeout:10000,maxBuffer:16*1024*1024});
test('Linux ARM64 and x64 use the verified portable archive',()=>{
 const linux=resolve(`dist/maven-build-${version}-linux.zip`);
 const manifest=JSON.parse(read(linux,'plugin.json'));
 assert.equal(manifest.version,version);
 const digest=path=>createHash('sha256').update(readFileSync(path)).digest('hex');
 assert.equal(digest(linux),digest(`dist/maven-build-${version}-darwin.zip`));
 assert.ok(read(linux,'lib/lithe-maven-extension.jar').length>0);
});
