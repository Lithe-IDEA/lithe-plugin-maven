// Real Maven + packaged SDK backend: native inheritance, a module override,
// local JAR compilation, external edits and cancellation-free process cleanup.
import assert from 'node:assert/strict';
import {createReport} from '../report.mjs';
import {capture} from './capture.mjs';
import {mkdtempSync,realpathSync,mkdirSync,writeFileSync,readFileSync,renameSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve,relative} from 'node:path';
import {runTool} from './process.mjs';
import {createHash} from 'node:crypto';
import {BackendClient,bounded} from './backend-client.mjs';
const scratch=realpathSync.native(mkdtempSync(join(tmpdir(),'lithe-maven-compiler-')));
const emptySettings=join(scratch,'empty-settings.xml');writeFileSync(emptySettings,'<settings/>');
const pkg=resolve(process.env.LITHE_TEST_PLUGIN_DIR??'dist/package/io.github.lithe-idea.maven-build');
const maven=process.env.MVN??'mvn';
const mavenSettings=['-gs',emptySettings,'-s',emptySettings];
const root=join(scratch,'workspace');mkdirSync(root);
const command=(tool,args,cwd=root)=>runTool(tool,args,{cwd,env:{...process.env,MAVEN_ARGS:''},timeout:120000});
const pom=(body)=>`<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>${body}</project>`;
const file=join(root,'pom.xml');
writeFileSync(file,pom('<groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><modules><module>a</module><module>b</module></modules><!-- keep-comment --><properties><privateToken>never-export-this</privateToken></properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version><configuration><release>11</release><compilerArgs><arg>-parameters</arg></compilerArgs></configuration></plugin></plugins></build>'));
for(const name of ['a','b']) {mkdirSync(join(root,name,'src/main/java'),{recursive:true});writeFileSync(join(root,name,'pom.xml'),pom(`<parent><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>${name}</artifactId>`));writeFileSync(join(root,name,'src/main/java/Main.java'),'public class Main { public static void main(String[] args) { System.out.print("ok"); } }');}
const digest=bytes=>'sha256:'+createHash('sha256').update(bytes).digest('hex');
let client;
const report=createReport('native-compiler');
const step=report.step;
async function model(){const output=join(scratch,'model.json');await command(maven,['-B','-ntp','-q',...mavenSettings,`-Dmaven.ext.class.path=${join(pkg,'lib/lithe-maven-extension.jar')}`,`-Dlithe.modelOutput=${output}`,'pre-clean']);const model=JSON.parse(readFileSync(output));for(const project of model.projects)project.path=relative(root,project.path).replaceAll('\\','/')||'.';return model;}
try{
 client=new BackendClient({java:process.env.LITHE_TEST_JAVA??'java',jar:join(pkg,'lib/lithe-maven-settings.jar'),args:[],hostRequests:{'lithe/host/workspace/getFolders':()=>({folders:[{rootID:'r',name:'workspace',path:root}]})}});
 await client.initialize(root,{functions:true});
 let operation=0;
 const invoke=async(functionID,params)=>client.request('lithe/function/invoke',{functionID,operationID:`compiler-${++operation}`,params,context:{contextID:'compiler',generation:1}});
 const read=async(language='zh-CN')=>invoke('compiler.settings',{context:{language,builds:[{root:'.',options:{},tool:{executable:maven},model:await model()}]}});
 let snapshot;
 await step('Read actual Maven inheritance without modifying a POM or exposing private fields',async()=>{const original=readFileSync(file);snapshot=await read();assert.deepEqual(readFileSync(file),original);assert.equal(JSON.stringify(snapshot).includes('never-export-this'),false);assert.equal(snapshot.sources.length,3);assert.deepEqual(snapshot.sources.find(s=>s.path==='pom.xml').fields.find(f=>f.id==='arguments').effectiveValue,['-parameters']);for(const source of snapshot.sources)assert.equal(source.fields.find(f=>f.id==='target').effectiveValue,'11');assert.equal(snapshot.sources.find(s=>s.path==='a/pom.xml').fields.find(f=>f.id==='target').value,null);});
 await step('Save only module a bytecode override, compile and inspect the class header',async()=>{const source=snapshot.sources.find(s=>s.path==='a/pom.xml');const stage=join(scratch,'staged-pom.xml');writeFileSync(stage,'');const reply=await invoke('compiler.prepareSettings',{operation:'editSource',sourceID:source.id,revision:source.revision,values:{target:'8'},outputFile:stage});assert.equal(reply.revision,digest(readFileSync(stage)));assert.equal(readFileSync(join(root,'a/pom.xml'),'utf8').includes('<release>8</release>'),false,'backend must not publish');renameSync(stage,join(root,'a/pom.xml'));await command(maven,['-B','-ntp','-q',...mavenSettings,'compile']);assert.equal(readFileSync(join(root,'a/target/classes/Main.class')).readUInt16BE(6),52);assert.equal(readFileSync(join(root,'b/target/classes/Main.class')).readUInt16BE(6),55);snapshot=await read();assert.equal(snapshot.sources.find(s=>s.path==='a/pom.xml').fields.find(f=>f.id==='target').effectiveValue,'8');assert.equal(snapshot.sources.find(s=>s.path==='a/pom.xml').fields.find(f=>f.id==='target').inheritedValue,'11');assert.match(readFileSync(file,'utf8'),/keep-comment/);});
 capture('zh',snapshot);capture('en',await read('en'));
 await step('A local JAR is written as a native dependency and used by Maven compilation',async()=>{const dir=join(scratch,'jar');mkdirSync(dir);writeFileSync(join(dir,'Library.java'),'package fixture; public class Library { public static String value() { return "jar-ok"; } }');await command('javac',['--release','8','-d',dir,join(dir,'Library.java')]);const jar=join(scratch,'External Library.jar');await command('jar',['--create','--file',jar,'-C',dir,'fixture']);const source=snapshot.sources.find(s=>s.path==='a/pom.xml'),stage=join(scratch,'library-pom.xml');writeFileSync(stage,'');await invoke('compiler.prepareSettings',{operation:'editSource',sourceID:source.id,revision:source.revision,values:{libraries:[jar]},outputFile:stage});renameSync(stage,join(root,'a/pom.xml'));writeFileSync(join(root,'a/src/main/java/Main.java'),'public class Main { public static void main(String[] args) { System.out.print(fixture.Library.value()); } }');await command(maven,['-B','-ntp','-q',...mavenSettings,'compile']);assert.equal((await command('java',['-cp',join(root,'a/target/classes')+requirePathSeparator()+jar,'Main'])).toString(),'jar-ok');snapshot=await read();});
 await step('A changed POM and a removed staging file are rejected without overwriting project content',async()=>{const source=snapshot.sources.find(s=>s.path==='a/pom.xml');const original=readFileSync(join(root,'a/pom.xml'));writeFileSync(join(root,'a/pom.xml'),Buffer.concat([original,Buffer.from('\n<!-- external -->')]));const stage=join(scratch,'conflict.xml');writeFileSync(stage,'');await assert.rejects(invoke('compiler.prepareSettings',{operation:'editSource',sourceID:source.id,revision:source.revision,values:{target:'11'},outputFile:stage}));assert.equal(readFileSync(stage).length,0);assert.match(readFileSync(join(root,'a/pom.xml'),'utf8'),/external/);});
 await step('SDK successes distinguish native saves from read projections',async()=>{assert.equal(client.diagnostics.some(f=>f.operation==='compiler.settings'&&f.kind==='operationFinished'),false);assert.equal(client.diagnostics.some(f=>f.operation==='compiler.prepareSettings'&&f.kind==='operationFinished'),true);assert.equal(JSON.stringify(client.diagnostics).includes('never-export-this'),false);});
 assert.equal(await client.close(),0);client=undefined;
}finally{
 if(client){client.kill();await bounded(client.exited,'compiler backend cleanup');}
 report.write();rmSync(scratch,{recursive:true,force:true});
}
function requirePathSeparator(){return process.platform==='win32'?';':':';}
