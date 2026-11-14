import json
from pathlib import Path
P=Path(__file__).parent;t=json.loads((P/'tokens.json').read_text())
(P/'kotlin/ai/cargo/tokens').mkdir(parents=True,exist_ok=True)
css=['/* Generated from tokens.json */',':root {']; kt=['// Generated from tokens.json','package ai.cargo.tokens','object CargoTokens {']
for group,values in t.items():
 for name,value in values.items():
  css += [f'  --cargo-{group}-{name}: {value if group=="color" else str(value)+"px"};']
  key=group+name[0].upper()+name[1:]
  kt += [f'    const val {key}: '+('Long = 0xFF'+value[1:]+'L' if group=='color' else 'Int = '+str(value))]
css+=['}'];kt+=['}']
(P/'tokens.css').write_text('\n'.join(css)+'\n');(P/'kotlin/ai/cargo/tokens/CargoTokens.kt').write_text('\n'.join(kt)+'\n')
