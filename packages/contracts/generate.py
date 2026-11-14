"""Deterministic schema-to-source generator for this deliberately bounded contract vocabulary."""
import json
from pathlib import Path
P=Path(__file__).parent
s=json.loads((P/'schema.json').read_text()); defs=s['$defs']
# Reject schema vocabulary changes until both runtime validators support them.
allowed={'$schema','$id','$ref','$defs','type','properties','required','additionalProperties','items','minItems','maxItems','minimum','maximum','minLength','maxLength','pattern','enum','const','oneOf','not'}
def check_vocabulary(rule):
 assert set(rule)<=allowed, 'Unsupported contract schema keyword: '+str(set(rule)-allowed)
 if 'type' in rule: assert rule['type'] in ['object','array','integer','string','boolean']
 for key in ['$defs','properties']:
  for child in rule.get(key,{}).values(): check_vocabulary(child)
 for key in ['items','not']:
  if key in rule:check_vocabulary(rule[key])
 for child in rule.get('oneOf',[]):check_vocabulary(child)
check_vocabulary(s)

for folder in ['kotlin/ai/cargo/contracts','ts']: (P/folder).mkdir(parents=True,exist_ok=True)
def typename(v,lang):
 if '$ref' in v:
  n=v['$ref'].split('/')[-1]
  return n
 if v.get('type')=='array':
  t=typename(v['items'],lang)
  return f'List<{t}>' if lang=='kt' else f'Array<{t}>'
 if lang=='ts' and 'enum' in v: return ' | '.join(json.dumps(x) for x in v['enum'])
 if lang=='ts' and 'const' in v: return json.dumps(v['const'])
 return ({'integer':'Long','string':'String','boolean':'Boolean'} if lang=='kt' else {'integer':'number','string':'string','boolean':'boolean'})[v['type']]
kt=['// Generated from schema.json. Do not edit.','package ai.cargo.contracts','import kotlinx.serialization.Serializable']
ts=['// Generated from schema.json. Do not edit.']
for n,v in defs.items():
 if v['type']=='object':
  kt+=['@Serializable',f'data class {n}(']
  ts+=[f'export interface {n} {{']
  for k,p in v['properties'].items():
   optional=k not in v['required']; t=typename(p,'kt')
   kt+=[f'    val {k}: {t}'+(' \u003f = null' if optional else '')+',']
   ts+=[f'  {k}'+('?' if optional else '')+': '+typename(p,'ts')+';']
  kt+=[')']; ts+=['}']
 elif 'enum' in v:
  kt+=['@Serializable',f'enum class {n} {{ '+', '.join(v['enum'])+' }']
  ts+=[f'export type {n} = '+typename(v,'ts')+';']
 else:
  kt+=[f'typealias {n} = '+typename(v,'kt')]
  ts+=[f'export type {n} = '+typename(v,'ts')+';']
(P/'kotlin/ai/cargo/contracts/Contracts.kt').write_text('\n'.join(kt)+'\n')
(P/'kotlin/ai/cargo/contracts/SchemaSource.kt').write_text('package ai.cargo.contracts\ninternal val schemaSource = """'+json.dumps(s,separators=(',',':')).replace('$', "${'$'}")+'"""\n')
(P/'ts/contracts.ts').write_text('\n'.join(ts)+'\n')
rot=json.loads((P/'rotations-v1.json').read_text())
(P/'kotlin/ai/cargo/contracts/Rotations.kt').write_text('package ai.cargo.contracts\nobject RotationsV1 {\n    val matrices: List<List<Int>> = listOf(\n'+''.join('        listOf('+','.join(map(str,m))+'),\n' for m in rot)+'    )\n}\n')
(P/'ts/rotations.ts').write_text('// Generated, stable orientationIndex v1\nexport const rotationsV1: ReadonlyArray<ReadonlyArray<number>> = '+json.dumps(rot)+';\n')
