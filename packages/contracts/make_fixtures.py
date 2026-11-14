import copy,itertools,json
from pathlib import Path
P=Path(__file__).parent
u='12345678-1234-1234-1234-123456789abc'; now='2026-11-03T00:00:00Z'; h='a'*64
base=dict(schemaVersion=1,id=u,revision=1,createdAt=now,updatedAt=now)
v=dict(x=120,y=230,z=340); pos=dict(x=12,y=34,z=56)
item=dict(**base,name='실측 상자',shapeKind='BOX',shapeProxy=[dict(origin=dict(x=0,y=0,z=0),size=v)],measuredDimensionsMm=v,errorMm=2,measurementSource='USER_TAPE',rotationRule='ANY24',stackable=False,removalPriority=1,confirmedDimensions=True)
profile=dict(**base,grade='DEMO_SYNTHETIC',bounds=dict(x=1200,y=900,z=1100),obstacles=[],errorMm=5,provenance='합성 시연용·실제차량 치수 아님',method='synthetic fixture',repeatMeasurements=[])
instance=dict(instanceId=u,itemId=u,itemRevision=1,poseMm=pos,orientationIndex=0,locked=True)
judgement=dict(placement='UNCERTAIN',opening='OPENING_UNMEASURED',path=dict(status='UNVERIFIED'),handling='OK',search=dict(status='NOT_RUN'),minimumClearanceMm=0,errorMm=2,conflictLocations=[],staleReasons=[],inputHash=h,safety='NOT_ASSESSED')
scene=dict(**base,profileId=u,profileRevision=1,instances=[instance],engineVersion='aabb-mm-v1',configurationRevision=1,judgementSnapshot=judgement,canonicalInputHash=h,checksum=h)
draft=dict(schemaVersion=1,sceneId=u,baseRevision=1,instances=[instance],undo=[],redo=[],camera=dict(target=pos,yawDegrees=30,pitchDegrees=10,distanceMm=2000))
env=dict(item=item,profile=profile,scene=scene,draft=draft,inbound=dict(draftId=u,pairingId=u,schemaVersion=1,payload=item,clientRevision=1))
cases=[]
def add(name,valid,mutate=lambda x:None):
 x=copy.deepcopy(env); mutate(x); cases.append(dict(name=name,valid=valid,value=x))
def search(x):return x['scene']['judgementSnapshot']['search']
for i in range(24):add(f'orientation-{i}',True,lambda x,i=i:x['scene']['instances'][0].update(orientationIndex=i))
for status in ['FOUND_PLACEMENT','CERTIFIED_INFEASIBLE_IN_MODEL','SEARCH_INTERRUPTED','NOT_RUN']:
 add(status,True,lambda x,status=status:search(x).update(status=status))
for reason in ['PROOF_CLASS_UNSUPPORTED','SUPPORT_CONSTRAINT_UNPROVEN','MULTI_ITEM_PROOF_UNSUPPORTED']:
 add(reason,True,lambda x,reason=reason:search(x).update(status='NO_CANDIDATE_UNCERTIFIED',reason=reason))
add('path-verified',True,lambda x:x['scene']['judgementSnapshot']['path'].update(status='VERIFIED_IN_MODEL',pathClass='Z_THEN_XY_AABB_SWEEP_V1'))
add('unsupported-no-bounds',True,lambda x:(x['profile'].update(grade='UNSUPPORTED'),x['profile'].pop('bounds')))
add('unsupported-fabricated-bounds',False,lambda x:x['profile'].update(grade='UNSUPPORTED'))
for status in ['CLEAR','CONFLICT','UNCERTAIN']:
 add('placement-'+status,True,lambda x,status=status:x['scene']['judgementSnapshot'].update(placement=status))
for status in ['FITS','DOES_NOT_FIT','UNCERTAIN','OPENING_UNMEASURED']:
 add('opening-'+status,True,lambda x,status=status:x['scene']['judgementSnapshot'].update(opening=status))
for rule in ['ANY24','KEEP_UPRIGHT','FLAT_ONLY']:
 add('rotation-rule-'+rule,True,lambda x,rule=rule:x['item'].update(rotationRule=rule))
add('negative-coordinate-bound',False,lambda x:x['scene']['instances'][0]['poseMm'].update(x=-10001))
add('fractional-revision',False,lambda x:x['scene'].update(revision=1.5))
add('orientation-24',False,lambda x:x['scene']['instances'][0].update(orientationIndex=24))
for n in [0,10001,1.5]: add('dimension-'+str(n),False,lambda x,n=n:x['item']['measuredDimensionsMm'].update(x=n))
add('unknown-enum',False,lambda x:search(x).update(status='IMPOSSIBLE'))
add('missing-reason',False,lambda x:search(x).update(status='NO_CANDIDATE_UNCERTIFIED'))
add('wrong-reason',False,lambda x:search(x).update(reason='PROOF_CLASS_UNSUPPORTED'))
add('missing-path-class',False,lambda x:x['scene']['judgementSnapshot']['path'].update(status='VERIFIED_IN_MODEL'))
add('future-schema',False,lambda x:x['item'].update(schemaVersion=2))
add('max-safe-revision',True,lambda x:x['scene'].update(revision=9007199254740991))
add('revision-overflow',False,lambda x:x['scene'].update(revision=9007199254740992))
add('invalid-uuid',False,lambda x:x['item'].update(id='not-uuid'))
add('decorative-mesh-input',False,lambda x:x['item'].update(mesh='not-geometry'))
add('name-emoji-120cp',True,lambda x:x['item'].update(name='😀'*120))
add('name-emoji-121cp',False,lambda x:x['item'].update(name='😀'*121))
# Keep raw JSON text as well as semantic golden values: parsing then stringifying
# would erase the exact decimal/exponent spelling before either runtime sees it.
raw_base=json.dumps(env,ensure_ascii=False)
def add_raw(name,valid,text):
 cases.append(dict(name=name,valid=valid,value=json.loads(text),text=text))
dimension_token='"measuredDimensionsMm": {"x": 120'
assert dimension_token in raw_base
for spelling in ['120.0','1.2e2','12e1','120.00000000000000001']:
 add_raw('integer-spelling-'+spelling,True,raw_base.replace(dimension_token,'"measuredDimensionsMm": {"x": '+spelling))
for spelling in ['120.5','1.205e2','1e309','10001.0']:
 # Infinity is valid JSON numeric syntax but outside every contract integer bound.
 text=raw_base.replace(dimension_token,'"measuredDimensionsMm": {"x": '+spelling)
 if spelling=='1e309':
  cases.append(dict(name='integer-spelling-'+spelling,valid=False,value=None,text=text))
 else:add_raw('integer-spelling-'+spelling,False,text)
for spelling in ['120d','120f','120F','120D','1.2e2f','120.','0120','+120']:
 text=raw_base.replace(dimension_token,'"measuredDimensionsMm": {"x": '+spelling,1)
 cases.append(dict(name='non-json-number-dimension-'+spelling,valid=False,value=None,text=text))
for spelling in ['01','+1','1d']:
 text=raw_base.replace('"schemaVersion": 1','"schemaVersion": '+spelling,1)
 cases.append(dict(name='non-json-number-schema-version-'+spelling,valid=False,value=None,text=text))
for field,canonical in [('locked','true'),('stackable','false'),('confirmedDimensions','true')]:
 for spelling in ['True','TRUE','tRuE','False','FALSE','fAlSe']:
  marker=f'"{field}": {canonical}'
  assert marker in raw_base
  text=raw_base.replace(marker,f'"{field}": {spelling}',1)
  cases.append(dict(name=f'non-json-boolean-{field}-{spelling}',valid=False,value=None,text=text))
add_raw('schema-version-decimal',True,raw_base.replace('"schemaVersion": 1','"schemaVersion": 1.0'))
add_raw('revision-exponent',True,raw_base.replace('"revision": 1','"revision": 1e0'))
for suffix,label in [('\n','LF'),('\r\n','CRLF'),('\u2028','LS'),('\u2029','PS')]:
 add('uuid-terminal-'+label,False,lambda x,suffix=suffix:x['item'].update(id=x['item']['id']+suffix))
 add('checksum-terminal-'+label,False,lambda x,suffix=suffix:x['scene'].update(checksum=x['scene']['checksum']+suffix))
 add('timestamp-terminal-'+label,False,lambda x,suffix=suffix:x['item'].update(createdAt=x['item']['createdAt']+suffix))
(P/'fixtures.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n')
# Signed permutation matrices, lexicographically ordered; identity first, stable forever in schema v1.
def det(m):return m[0]*(m[4]*m[8]-m[5]*m[7])-m[1]*(m[3]*m[8]-m[5]*m[6])+m[2]*(m[3]*m[7]-m[4]*m[6])
rot=[]
for p in itertools.permutations(range(3)):
 for signs in itertools.product([-1,1],repeat=3):
  m=[0]*9
  for row in range(3):m[row*3+p[row]]=signs[row]
  if det(m)==1:rot.append(m)
identity=[1,0,0,0,1,0,0,0,1];rot= [identity]+[m for m in sorted(rot) if m!=identity]
(P/'rotations-v1.json').write_text(json.dumps(rot,indent=2)+'\n')
(P/'migration-axis-v0-v1.json').write_text(json.dumps({'old':{'x':12,'y':56,'z':34},'new':pos,'rule':'v0 y-depth/z-height -> v1 y-height/z-depth; legacy origin requires explicit frame provenance; no silent handedness inference'},indent=2)+'\n')
