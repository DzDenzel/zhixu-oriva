import ts from 'typescript';

/** Static declaration check only, not proof of unknown code's runtime behavior. */
export function assertGlobalEntry(source, name) {
  const file = ts.createSourceFile('index.js', source, ts.ScriptTarget.ES2020, true, ts.ScriptKind.JS);
  const callable = file.statements.some(statement => {
    if (ts.isFunctionDeclaration(statement)) return statement.name?.text === name && Boolean(statement.body);
    if (!ts.isVariableStatement(statement)) return false;
    return statement.declarationList.declarations.some(declaration => {
      if (!ts.isIdentifier(declaration.name) || declaration.name.text !== name) return false;
      let initializer = declaration.initializer;
      while (initializer && ts.isParenthesizedExpression(initializer)) initializer = initializer.expression;
      return initializer && (ts.isArrowFunction(initializer) || ts.isFunctionExpression(initializer));
    });
  });
  if (!callable) throw new Error(`entryPoint ${name} must be a top-level callable function declaration`);
}

/** Verify the TS entry export has a callable value, including re-export aliases. */
export function assertCallableExport(path, name) {
  const program = ts.createProgram([path], { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Bundler, noEmit: true, types: [] });
  const file = program.getSourceFile(path);
  const checker = program.getTypeChecker();
  const module = file && checker.getSymbolAtLocation(file);
  let symbol = module && checker.getExportsOfModule(module).find(item => item.name === name);
  if (!symbol) throw new Error(`No matching export for entryPoint ${name}`);
  if (symbol.flags & ts.SymbolFlags.Alias) symbol = checker.getAliasedSymbol(symbol);
  if (!(symbol.flags & ts.SymbolFlags.Value) || checker.getTypeOfSymbolAtLocation(symbol, file).getCallSignatures().length === 0) {
    throw new Error(`entryPoint ${name} must be callable`);
  }
}
