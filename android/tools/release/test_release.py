import importlib.util
from pathlib import Path
import tempfile,subprocess,unittest,zipfile,json,hashlib
SPEC=importlib.util.spec_from_file_location('release',Path(__file__).with_name('release.py'))
r=None
if SPEC and SPEC.loader and Path(SPEC.origin).exists():
 r=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(r)

class ReleaseTests(unittest.TestCase):
 def setUp(self):
  self.assertIsNotNone(r,'Missing reusable release tool')
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name).resolve()
  for args in [['init','-b','main'],['config','user.name','Release Test'],['config','user.email','release@example.invalid'],['config','core.autocrlf','false']]:self.git(*args)
  (self.root/'android/app').mkdir(parents=True)
  (self.root/'android/app/build.gradle.kts').write_text('versionCode = 2\nversionName = "2.0.0"\n',encoding='utf8')
  (self.root/'.gitignore').write_text('/work/\n/release/\n',encoding='utf8')
  (self.root/'README.md').write_bytes('中文\n'.encode('utf8'))
  self.git('add','.');self.git('commit','-m','initial')
 def git(self,*args):return subprocess.check_output(['git',*args],cwd=self.root,text=True,stderr=subprocess.DEVNULL).strip()
 def test_plan_uses_current_repository_and_never_clones(self):
  plan=r.make_plan(self.root)
  self.assertEqual(plan['root'],str(self.root.resolve()))
  self.assertEqual(plan['version'],'2.0.0')
  self.assertNotIn('clone',' '.join(str(x) for x in plan['commands']))
  self.assertIn(str(self.root/'android/.gradle-home'),[arg for command in plan['commands'] for arg in command])
 def test_second_release_reuses_same_repo_and_isolates_outputs(self):
  before=r.make_plan(self.root)
  p=self.root/'android/app/build.gradle.kts';p.write_text('versionCode = 3\nversionName = "2.0.1"\n',encoding='utf8')
  after=r.make_plan(self.root)
  self.assertEqual(before['root'],after['root']);self.assertNotEqual(before['assets'],after['assets'])
  self.assertEqual(after['tag'],'v2.0.1')
 def test_archive_is_exact_commit_and_excludes_ignored_files(self):
  (self.root/'work').mkdir();(self.root/'work/private.txt').write_text('private',encoding='utf8')
  out=self.root/'source.zip';head=self.git('rev-parse','HEAD')
  r.archive_source(self.root,head,'2.0.0',out)
  with zipfile.ZipFile(out) as z:
   self.assertEqual(z.read('zhixu-oriva-2.0.0/README.md'),'中文\n'.encode('utf8'))
   self.assertFalse(any('private' in n for n in z.namelist()))
 def test_dirty_tree_blocks_build_and_package(self):
  (self.root/'README.md').write_text('changed',encoding='utf8')
  with self.assertRaisesRegex(r.ReleaseError,'commit'):r.require_clean(self.root)
 def test_build_evidence_rejects_another_commit_or_changed_apk(self):
  apk=self.root/'sample.apk';apk.write_bytes(b'apk')
  record={'commit':'old','unsignedSHA256':r.sha256(apk)}
  with self.assertRaisesRegex(r.ReleaseError,'commit'):r.check_build_evidence(record,'new',apk)
  record['commit']='new';apk.write_bytes(b'changed')
  with self.assertRaisesRegex(r.ReleaseError,'APK'):r.check_build_evidence(record,'new',apk)
 def test_existing_asset_is_reused_only_when_digest_and_size_match(self):
  p=self.root/'a.apk';p.write_bytes(b'apk')
  calls=[]
  def upload(name,data):calls.append(name);return {'name':name,'size':len(data),'digest':'sha256:'+hashlib.sha256(data).hexdigest()}
  item=upload(p.name,p.read_bytes());calls.clear()
  r.ensure_asset(p,{p.name:item},upload);self.assertEqual(calls,[])
  r.ensure_asset(p,{},upload);self.assertEqual(calls,[p.name])
  item['digest']='sha256:'+'0'*64
  with self.assertRaisesRegex(r.ReleaseError,'digest'):r.ensure_asset(p,{p.name:item},upload)
 def test_failed_upload_is_not_silently_accepted(self):
  p=self.root/'a.apk';p.write_bytes(b'apk')
  with self.assertRaisesRegex(r.ReleaseError,'digest'):r.ensure_asset(p,{},lambda n,d:{'name':n,'size':len(d),'digest':None})
 def test_tag_is_immutable(self):
  r.check_remote_tag({'refs/tags/v2.0.0':'tag-object','refs/tags/v2.0.0^{}':'same'},'v2.0.0','same')
  with self.assertRaisesRegex(r.ReleaseError,'tag'):r.check_remote_tag({'refs/tags/v2.0.0':'other'},'v2.0.0','same')
 def test_checksums_refuse_path_traversal_and_mismatches(self):
  assets=self.root/'assets';assets.mkdir();(assets/'a.apk').write_bytes(b'apk')
  (assets/'SHA256SUMS').write_text(r.sha256(assets/'a.apk')+'  a.apk\n',encoding='utf8')
  self.assertEqual(r.verify_checksums(assets),['a.apk'])
  (assets/'SHA256SUMS').write_text('0'*64+'  ../README.md\n',encoding='utf8')
  with self.assertRaises(r.ReleaseError):r.verify_checksums(assets)
 def test_cloning_is_explicit_opt_in(self):
  args=r.parser().parse_args(['verify']);self.assertFalse(args.clone_check)


class PublishRecoveryTests(unittest.TestCase):
 setUp=ReleaseTests.setUp
 git=ReleaseTests.git
 def test_resume_after_tag_push_and_repeat_publication(self):
  from unittest.mock import patch
  from types import SimpleNamespace
  plan=r.make_plan(self.root);directory=Path(plan['assets']);directory.mkdir(parents=True)
  head=self.git('rev-parse','HEAD');name=plan['version']
  names=['zhixu-oriva-'+name+'.apk','zhixu-oriva-'+name+'-source.zip','oriva-release.cer','release-manifest.json']
  for asset in names[:3]:(directory/asset).write_bytes(asset.encode())
  r.write_json(directory/'release-manifest.json',dict(versionName=name,commit=head,certificateSHA256='A'*64))
  (directory/'SHA256SUMS').write_text(''.join(r.sha256(directory/asset)+'  '+asset+'\n' for asset in names),encoding='utf8')
  notes=self.root/'work/notes.md';notes.parent.mkdir(parents=True);notes.write_text('Release scope',encoding='utf8')
  args=SimpleNamespace(repo='owner/repo',notes=str(notes));refs={};state={'release':None,'create':0,'uploads':0,'break':True,'uploadBreak':True}
  original_git=r.git
  def fake_git(root,*items):
   if items==('remote','get-url','origin'):return 'git@github.com:owner/repo.git'
   if items==('ls-remote','origin'):return '\n'.join(value+'\t'+key for key,value in refs.items())
   return original_git(root,*items)
  def fake_execute(command,root,env,logfile=None):
   if command[:3]==['git','push','-u']:refs['refs/heads/main']=head
   elif command[:3]==['git','tag','-a']:self.git(*command[1:])
   elif command[:3]==['git','push','origin']:refs['refs/tags/'+plan['tag']]=head
   return ''
  class FakeGithub:
   def __init__(self,repo):self.base='https://api.github.com/repos/'+repo
   def call(self,url,method='GET',payload=None,missing_ok=False):
    if method=='GET':
     if '/releases/tags/' in url:return state['release'] if state['release'] and not state['release']['draft'] else None
     if '/releases?' in url:return [state['release']] if state['release'] else []
     return state['release']
    if method=='POST' and url==self.base+'/releases':
     if state['break']:state['break']=False;raise r.ReleaseError('simulated interruption after tag push')
     if state['release'] is not None:raise r.ReleaseError('Duplicate draft creation')
     state['create']+=1
     state['release']=dict(id=1,tag_name=plan['tag'],target_commitish=head,draft=True,assets=[],upload_url='https://uploads.github.com/assets{?name}',html_url='https://github.com/owner/repo/releases/tag/'+plan['tag'])
     return state['release']
    if isinstance(payload,bytes):
     if state['uploads']==2 and state['uploadBreak']:
      state['uploadBreak']=False;raise r.ReleaseError('simulated asset interruption')
     asset=r.urllib.parse.parse_qs(r.urllib.parse.urlparse(url).query)['name'][0]
     item=dict(name=asset,size=len(payload),digest='sha256:'+hashlib.sha256(payload).hexdigest());state['release']['assets'].append(item);state['uploads']+=1;return item
    if method=='PATCH':state['release'].update(payload);return state['release']
    raise AssertionError((url,method))
  with patch.object(r,'git',fake_git),patch.object(r,'execute',fake_execute),patch.object(r,'Github',FakeGithub):
   with self.assertRaisesRegex(r.ReleaseError,'simulated interruption'):r.publish(self.root,plan,args,{})
   self.assertIn('refs/tags/'+plan['tag'],refs)
   with self.assertRaisesRegex(r.ReleaseError,'asset interruption'):r.publish(self.root,plan,args,{})
   self.assertTrue(state['release']['draft']);self.assertEqual(state['uploads'],2)
   statefile=Path(plan['evidence'])/'github-release-state.json'
   # Prove saved-ID recovery, then discover the same draft when state was lost.
   self.assertEqual(r.find_release(r.Github(args.repo),plan['tag'],head,statefile)['id'],1)
   statefile.unlink()
   r.publish(self.root,plan,args,{})
   self.assertFalse(state['release']['draft']);self.assertEqual(state['create'],1);self.assertEqual(state['uploads'],5)
   statefile.unlink()
   r.publish(self.root,plan,args,{})
   self.assertEqual(state['create'],1);self.assertEqual(state['uploads'],5)

class AuditAndWindowsTests(unittest.TestCase):
 setUp=ReleaseTests.setUp
 git=ReleaseTests.git
 def test_credentials_are_rejected(self):
  samples=[b'jarsigner -store'+b'pass example-only-password',b'https'+b'://example-user:example-password@example.invalid/path',b'password: example-only-password']
  for sample in samples:
   with self.subTest(sample=sample):
    buffer=r.io.BytesIO()
    with zipfile.ZipFile(buffer,'w') as z:z.writestr('zhixu-oriva-2.0.0/backend/application.yml',sample)
    with zipfile.ZipFile(r.io.BytesIO(buffer.getvalue())) as z:
     with self.assertRaises(r.ReleaseError):r.audit_archive(z)
 def test_required_backend_env_contract_is_preserved(self):
  buffer=r.io.BytesIO()
  with zipfile.ZipFile(buffer,'w') as z:z.writestr('zhixu-oriva-2.0.0/backend/planlock-app/src/main/resources/application.yml','password: ${PLANLOCK_DB_PASSWORD:example-only-password}')
  with zipfile.ZipFile(r.io.BytesIO(buffer.getvalue())) as z:
   with self.assertRaises(r.ReleaseError):r.audit_archive(z)
 def test_invalid_api_endpoints_are_rejected_by_cli(self):
  script=Path(r.__file__).resolve()
  for endpoint in ['https:///api/v1/','https://example.invalid:bad/api/v1/','https://bad host/api/v1/']:
   result=subprocess.run([r.sys.executable,str(script),'plan','--root',str(self.root),'--api-base-url',endpoint],capture_output=True,text=True)
   self.assertNotEqual(result.returncode,0,endpoint)
 @unittest.skipUnless(r.os.name=='nt','Windows batch invocation')
 def test_batch_script_and_literal_arguments_with_spaces(self):
  directory=self.root/'folder with spaces';directory.mkdir();script=directory/'echo test.cmd'
  script.write_bytes(b'@echo off\r\necho [%1]\r\necho [%2]\r\n')
  output=r.execute([str(script),'argument with spaces','literal&value'],self.root,r.os.environ.copy())
  self.assertIn('["argument with spaces"]',output);self.assertIn('["literal&value"]',output)

if __name__=='__main__':unittest.main()
