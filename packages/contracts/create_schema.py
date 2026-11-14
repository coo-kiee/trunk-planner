"""Author the versioned schema; generate.py emits consumers from this schema only."""
import json
from pathlib import Path
R=lambda n:{'$ref':'#/$defs/'+n}
S=lambda **kw:dict(type='string',**kw)
I=lambda lo=0,hi=10000:dict(type='integer',minimum=lo,maximum=hi)
A=lambda t,lo=0,hi=100:dict(type='array',items=t,minItems=lo,maxItems=hi)
def O(props,optional=(),**kw): return dict(type='object',properties=props,required=[k for k in props if k not in optional],additionalProperties=False,**kw)
E=lambda *v:dict(type='string',enum=list(v))
d={}
d['Uuid']=S(pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
d['Revision']=I(1,9007199254740991)
d['Timestamp']=S(pattern=r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$')
d['Hash']=S(pattern='^[0-9a-f]{64}$')
d['Vec3Mm']=O({k:I(-10000,10000) for k in ('x','y','z')})
d['DimensionsMm']=O({k:I(1,10000) for k in ('x','y','z')})
d['BoxProxy']=O({'origin':R('Vec3Mm'),'size':R('DimensionsMm')})
d['ShapeKind']=E('BOX','L_SHAPE','CYLINDER_BOUNDING_BOX')
d['RotationRule']=E('ANY24','KEEP_UPRIGHT','FLAT_ONLY')
d['MeasurementSource']=E('USER_TAPE','REPEATED_TAPE','PUBLISHED_PARTIAL','DEMO_SYNTHETIC')
d['ProfileGrade']=E('VERIFIED_MEASURED','USER_MEASURED','PUBLISHED_PARTIAL','DEMO_SYNTHETIC','UNSUPPORTED')
d['PlacementStatus']=E('CLEAR','CONFLICT','UNCERTAIN')
d['OpeningStatus']=E('FITS','DOES_NOT_FIT','UNCERTAIN','OPENING_UNMEASURED')
d['HandlingStatus']=E('OK','VIOLATION')
d['SearchStatus']=E('FOUND_PLACEMENT','CERTIFIED_INFEASIBLE_IN_MODEL','NO_CANDIDATE_UNCERTIFIED','SEARCH_INTERRUPTED','NOT_RUN')
d['UncertifiedReason']=E('PROOF_CLASS_UNSUPPORTED','SUPPORT_CONSTRAINT_UNPROVEN','MULTI_ITEM_PROOF_UNSUPPORTED')
d['SearchResult']=O({'status':R('SearchStatus'),'reason':R('UncertifiedReason')},['reason'],oneOf=[
 {'properties':{'status':{'const':'NO_CANDIDATE_UNCERTIFIED'}},'required':['reason']},
 {'properties':{'status':{'enum':['FOUND_PLACEMENT','CERTIFIED_INFEASIBLE_IN_MODEL','SEARCH_INTERRUPTED','NOT_RUN']}},'not':{'required':['reason']}}])
d['PathResult']=O({'status':E('VERIFIED_IN_MODEL','NOT_FOUND_IN_CHECKED_CLASS','UNVERIFIED'),'pathClass':E('Z_THEN_XY_AABB_SWEEP_V1')},['pathClass'],oneOf=[
 {'properties':{'status':{'const':'VERIFIED_IN_MODEL'}},'required':['pathClass']},
 {'properties':{'status':{'enum':['NOT_FOUND_IN_CHECKED_CLASS','UNVERIFIED']}},'not':{'required':['pathClass']}}])
base={'schemaVersion':dict(type='integer',const=1),'id':R('Uuid'),'revision':R('Revision'),'createdAt':R('Timestamp'),'updatedAt':R('Timestamp')}
d['ItemVersion']=O({**base,'name':S(minLength=1,maxLength=120),'photoRef':S(minLength=1,maxLength=256),'shapeKind':R('ShapeKind'),'shapeProxy':A(R('BoxProxy'),1,16),'measuredDimensionsMm':R('DimensionsMm'),'errorMm':I(0,1000),'measurementSource':R('MeasurementSource'),'rotationRule':R('RotationRule'),'stackable':{'type':'boolean'},'removalPriority':I(0,100),'confirmedDimensions':{'type':'boolean'}},['photoRef'])
d['ProfileVersion']=O({**base,'grade':R('ProfileGrade'),'bounds':R('DimensionsMm'),'obstacles':A(R('BoxProxy')),'opening':R('DimensionsMm'),'errorMm':I(0,1000),'provenance':S(minLength=1,maxLength=1000),'sourceURL':S(pattern='^https://',maxLength=2048),'measuredAt':R('Timestamp'),'method':S(minLength=1,maxLength=1000),'repeatMeasurements':A(R('DimensionsMm'),0,20)},['bounds','opening','sourceURL','measuredAt'],oneOf=[{'properties':{'grade':{'const':'UNSUPPORTED'}},'not':{'required':['bounds']}},{'properties':{'grade':{'enum':['VERIFIED_MEASURED','USER_MEASURED','PUBLISHED_PARTIAL','DEMO_SYNTHETIC']}},'required':['bounds']}])
d['Instance']=O({'instanceId':R('Uuid'),'itemId':R('Uuid'),'itemRevision':R('Revision'),'poseMm':R('Vec3Mm'),'orientationIndex':I(0,23),'locked':{'type':'boolean'}})
d['JudgementSnapshot']=O({'placement':R('PlacementStatus'),'opening':R('OpeningStatus'),'path':R('PathResult'),'handling':R('HandlingStatus'),'search':R('SearchResult'),'minimumClearanceMm':I(-10000,10000),'errorMm':I(0,1000),'conflictLocations':A(R('Vec3Mm')),'staleReasons':A(S(minLength=1,maxLength=120),0,20),'inputHash':R('Hash'),'safety':dict(type='string',const='NOT_ASSESSED')})
d['SceneRevision']=O({**base,'parentRevision':R('Revision'),'profileId':R('Uuid'),'profileRevision':R('Revision'),'instances':A(R('Instance'),0,100),'engineVersion':S(minLength=1,maxLength=40),'configurationRevision':R('Revision'),'judgementSnapshot':R('JudgementSnapshot'),'canonicalInputHash':R('Hash'),'checksum':R('Hash')},['parentRevision'])
d['Camera']=O({'target':R('Vec3Mm'),'yawDegrees':I(-360,360),'pitchDegrees':I(-90,90),'distanceMm':I(1,30000)})
d['EditDraft']=O({'schemaVersion':dict(type='integer',const=1),'sceneId':R('Uuid'),'baseRevision':R('Revision'),'instances':A(R('Instance')),'undo':A(A(R('Instance')),0,100),'redo':A(A(R('Instance')),0,100),'camera':R('Camera')})
d['ItemDraft']=O({'draftId':R('Uuid'),'pairingId':R('Uuid'),'baseItemId':R('Uuid'),'baseItemRevision':R('Revision'),'schemaVersion':dict(type='integer',const=1),'payload':R('ItemVersion'),'clientRevision':R('Revision')},['baseItemId','baseItemRevision'])
d['Envelope']=O({'item':R('ItemVersion'),'profile':R('ProfileVersion'),'scene':R('SceneRevision'),'draft':R('EditDraft'),'inbound':R('ItemDraft')})
schema={'$schema':'https://json-schema.org/draft/2020-12/schema','$id':'https://cargo.invalid/contracts/v1','$ref':'#/$defs/Envelope','$defs':d}
Path(__file__).with_name('schema.json').write_text(json.dumps(schema,indent=2)+'\n')
