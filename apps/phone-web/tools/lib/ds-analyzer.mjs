// Static half of the DS check: real call sites through the TypeScript AST, and every style declaration against the generated tokens.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import ts from 'typescript';

const SHARED_COMPONENTS = new Set(['Button', 'Field', 'Status', 'Panel', 'TextLink']);
const RAW_CONTROLS = new Set(['button', 'input', 'select', 'textarea', 'label', 'a']);
const COLOR_LITERAL = /#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(/;
const RENDER_3D = /from\s+['"]three['"]|require\(['"]three['"]\)|@react-three|webgl|WebGLRenderingContext|babylon/i;

function walk(dir, predicate) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return walk(path, predicate);
    return predicate(path) ? [path] : [];
  });
}

const literalProp = (attribute) => {
  const init = attribute.initializer;
  if (!init) return true;
  if (ts.isStringLiteral(init)) return init.text;
  if (ts.isJsxExpression(init) && init.expression && ts.isStringLiteralLike(init.expression)) return init.expression.text;
  return '{expr}';
};

/** Reports real JSX usage of shared components, raw controls, inline styles and colour literals in one TSX file. */
export function analyzeTsx(path, root) {
  const text = readFileSync(path, 'utf8');
  const source = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const file = relative(root, path);
  const calls = [];
  const violations = [];
  const imported = new Set();
  const used = new Set();
  const line = (node) => source.getLineAndCharacterOfPosition(node.getStart()).line + 1;

  const visit = (node) => {
    if (ts.isImportDeclaration(node) && node.importClause?.namedBindings && ts.isNamedImports(node.importClause.namedBindings)) {
      for (const element of node.importClause.namedBindings.elements) {
        if (SHARED_COMPONENTS.has(element.name.text)) imported.add(element.name.text);
      }
    }
    if (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) {
      const name = node.tagName.getText(source);
      const props = {};
      for (const attribute of node.attributes.properties) {
        if (!ts.isJsxAttribute(attribute)) continue;
        const propName = attribute.name.getText(source);
        props[propName] = literalProp(attribute);
        if (propName === 'style') violations.push({ file, line: line(attribute), rule: 'inline-style', detail: name });
      }
      if (SHARED_COMPONENTS.has(name)) {
        used.add(name);
        calls.push({ file, line: line(node), component: name, ...pick(props, ['variant', 'tone', 'kind', 'name', 'level', 'state']) });
      } else if (RAW_CONTROLS.has(name)) {
        violations.push({ file, line: line(node), rule: 'raw-control', detail: name });
      } else if (name === 'canvas') {
        violations.push({ file, line: line(node), rule: 'canvas-on-screen', detail: 'phone screens render no 3D or judgement view' });
      }
    }
    if ((ts.isStringLiteralLike(node) || ts.isJsxText(node)) && COLOR_LITERAL.test(node.getText(source))) {
      violations.push({ file, line: line(node), rule: 'color-literal', detail: node.getText(source).slice(0, 40) });
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  for (const component of imported) {
    if (!used.has(component)) violations.push({ file, line: 1, rule: 'import-only', detail: component });
  }
  return { file, calls, violations };
}

const pick = (object, keys) => Object.fromEntries(keys.filter((key) => object[key] !== undefined).map((key) => [key, object[key]]));

// Properties whose values must come from tokens, with the reason for anything else being acceptable.
const TOKEN_PROPERTIES = /^(color|background|background-color|border|border-color|border-width|border-left-width|border-radius|outline|outline-offset|accent-color|padding|padding-left|padding-right|padding-top|padding-bottom|margin|margin-left|gap|font-size|min-height|width|height|max-width|max-height)$/;
const ALLOWED_VALUE = /^(0|auto|100%|transparent|inherit|none|solid|calc\(.*\)|var\(.*\)|.*var\(--cargo-[^)]*\).*|0 auto|0 0)$/;
export const EXCEPTIONS = [
  { rule: '--cargo-hairline: 1px', reason: '1px 경계선은 토큰에 없는 최소 hairline 예외. 다른 곳은 이 별칭으로만 사용' },
  { rule: 'font-weight, line-height, font-family', reason: '토큰에 정의되지 않음. 색·간격·크기 의미를 담지 않는 서체 속성' },
  { rule: 'layout keywords (display, grid-template-columns: repeat(..), flex 등)', reason: '배치 구조이며 값이 토큰 계산식 또는 비율' },
  { rule: 'width: 100% / max-width: 100%', reason: '비율. 절대값 복제 아님' },
];

/** Every declaration in one CSS file: literal colours and sizes are violations; each var() must name a defined token. */
export function analyzeCss(path, root, definedTokens) {
  const file = relative(root, path);
  const text = readFileSync(path, 'utf8').replace(/\/\*[\s\S]*?\*\//g, (comment) => ' '.repeat(comment.length));
  const violations = [];
  const usages = [];
  const declaration = /(^|[{;\s])(--[\w-]+|[a-z-]+)\s*:\s*([^;{}]+);/g;
  let match;
  while ((match = declaration.exec(text)) !== null) {
    const property = match[2];
    const value = match[3].trim();
    const lineNumber = text.slice(0, match.index + match[1].length).split('\n').length;
    if (COLOR_LITERAL.test(value)) violations.push({ file, line: lineNumber, rule: 'color-literal', detail: `${property}: ${value}` });
    for (const reference of value.matchAll(/var\((--[\w-]+)\)/g)) {
      usages.push({ file, line: lineNumber, property, token: reference[1] });
      if (reference[1].startsWith('--status-')) continue; // A per-tone local variable, itself checked where it is set.
      if (!definedTokens.has(reference[1])) violations.push({ file, line: lineNumber, rule: 'unknown-token', detail: `${property}: ${value}` });
    }
    if (property === '--cargo-hairline') continue;
    if (TOKEN_PROPERTIES.test(property) && !ALLOWED_VALUE.test(value)) violations.push({ file, line: lineNumber, rule: 'literal-value', detail: `${property}: ${value}` });
  }
  return { file, usages, violations };
}

export function readDefinedTokens(cssPaths) {
  const defined = new Map();
  for (const path of cssPaths) {
    for (const match of readFileSync(path, 'utf8').matchAll(/(--cargo-[\w-]+)\s*:\s*([^;]+);/g)) defined.set(match[1], match[2].trim());
  }
  return defined;
}

export function scanSources(appRoot, repoRoot) {
  const tokensCss = join(repoRoot, 'packages/design-tokens/tokens.css');
  const semanticCss = join(appRoot, 'shared/ui/semantic-tokens.css');
  const defined = readDefinedTokens([tokensCss, semanticCss]);
  const tsxFiles = [...walk(join(appRoot, 'screens'), (path) => path.endsWith('.tsx')), ...walk(join(appRoot, 'app'), (path) => path.endsWith('.tsx'))];
  const cssFiles = [...walk(join(appRoot, 'screens'), (path) => path.endsWith('.css')), join(appRoot, 'shared/ui/ui.module.css'), join(appRoot, 'app/globals.css')];
  const tsx = tsxFiles.map((path) => analyzeTsx(path, appRoot));
  const css = cssFiles.map((path) => analyzeCss(path, appRoot, defined));
  const uiTsx = walk(join(appRoot, 'shared/ui'), (path) => path.endsWith('.tsx')).map((path) => {
    const violations = [...readFileSync(path, 'utf8').matchAll(/#[0-9a-fA-F]{6}\b|\brgb\(/g)].map((hit) => ({ file: relative(appRoot, path), line: 0, rule: 'color-literal', detail: hit[0] }));
    return { file: relative(appRoot, path), violations };
  });
  const render3d = [...walk(join(appRoot, 'screens'), (path) => /\.(ts|tsx)$/.test(path)), ...walk(join(appRoot, 'features'), (path) => /\.(ts|tsx)$/.test(path)), ...walk(join(appRoot, 'entities'), (path) => /\.(ts|tsx)$/.test(path))]
    .filter((path) => RENDER_3D.test(readFileSync(path, 'utf8'))).map((path) => relative(appRoot, path));
  const packageJson = JSON.parse(readFileSync(join(appRoot, 'package.json'), 'utf8'));
  const render3dDeps = Object.keys({ ...packageJson.dependencies, ...packageJson.devDependencies }).filter((name) => /^three$|@react-three|babylon/.test(name));
  return { defined, tsx, css, uiTsx, render3d, render3dDeps };
}
