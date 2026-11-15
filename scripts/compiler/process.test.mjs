import {test} from 'node:test';
import assert from 'node:assert/strict';
import {runTool} from './process.mjs';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
test('Windows resolves a bare batch launcher through PATH and preserves spaces',{skip:process.platform!=='win32'},async()=>{
 const root=mkdtempSync(join(tmpdir(),'lithe batch-'));
 try{
  writeFileSync(join(root,'lithe-fixture.cmd'),'@echo off\r\necho %~1\r\n');
  const output=await runTool('lithe-fixture',['space inside'],{env:{...process.env,PATH:root+';'+process.env.PATH},timeout:5000});
  assert.equal(output.toString().trim(),'space inside');
 }finally{rmSync(root,{recursive:true,force:true});}
});
test('bounded tool runner returns output and rejects a failed command',async()=>{
 assert.equal((await runTool(process.execPath,['-e','process.stdout.write("ready")'])).toString(),'ready');
 await assert.rejects(runTool(process.execPath,['-e','process.exit(3)']),/Tool failed/);
});
test('a tool deadline terminates an owned tree including its descendant',async()=>{
 const code=`const {spawn}=require('node:child_process');spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'inherit'});setInterval(()=>{},1000);`;
 await assert.rejects(runTool(process.execPath,['-e',code],{timeout:2500}),/Tool timed out/);
});
