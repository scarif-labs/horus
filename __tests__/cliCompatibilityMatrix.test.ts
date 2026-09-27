import {
  CLI_COMPATIBILITY_SCHEMA,
  CLI_IDS,
  createEmptyCompatibilityMatrix,
  summarizeCompatibilityMatrix,
  validateCompatibilityMatrix,
} from '../src/terminal/compatibility';

describe('Alpine CLI compatibility matrix', () => {
  it('creates four explicit records with four untested stages each', () => {
    const matrix = createEmptyCompatibilityMatrix();
    expect(matrix.schema).toBe(CLI_COMPATIBILITY_SCHEMA);
    expect(matrix.records.map(record => record.cli)).toEqual(CLI_IDS);
    expect(summarizeCompatibilityMatrix(matrix)).toEqual({
      passedStages: 0,
      failedStages: 0,
      untestedStages: 16,
      unsupportedStages: 0,
    });
    expect(validateCompatibilityMatrix(matrix)).toEqual({valid: true, matrix});
  });

  it('counts explicit pass, fail, unsupported, and not-tested results', () => {
    const matrix = createEmptyCompatibilityMatrix();
    const first = matrix.records[0];
    const updated = {
      ...matrix,
      records: matrix.records.map(record => record.cli === first.cli
        ? {
            ...record,
            stages: {
              ...record.stages,
              install: {status: 'pass' as const, version: '1.0.0'},
              'version-doctor': {status: 'fail' as const, detail: 'doctor unavailable'},
              execution: {status: 'unsupported' as const, detail: 'requires bwrap'},
            },
          }
        : record),
    };
    expect(summarizeCompatibilityMatrix(updated)).toEqual({
      passedStages: 1,
      failedStages: 1,
      untestedStages: 13,
      unsupportedStages: 1,
    });
    expect(validateCompatibilityMatrix(updated).valid).toBe(true);
  });

  it('accepts an explicit sandbox capability result', () => {
    const matrix = createEmptyCompatibilityMatrix();
    const updated = {
      ...matrix,
      records: matrix.records.map(record => record.cli === 'codex-cli'
        ? {...record, sandbox: {status: 'unsupported' as const, detail: 'proot_sandbox_exit_182'}}
        : record),
    };
    expect(validateCompatibilityMatrix(updated).valid).toBe(true);
  });

  it('rejects duplicate or incomplete records, unknown stages, and credential-shaped text', () => {
    const matrix = createEmptyCompatibilityMatrix();
    const duplicate = {...matrix, records: [...matrix.records, matrix.records[0]]};
    expect(validateCompatibilityMatrix(duplicate)).toEqual(expect.objectContaining({valid: false}));

    const unknownStage = createEmptyCompatibilityMatrix();
    const record = unknownStage.records[0] as unknown as {stages: Record<string, unknown>};
    record.stages = {...record.stages, doctor: {status: 'pass'}};
    expect(validateCompatibilityMatrix(unknownStage)).toEqual(expect.objectContaining({valid: false}));

    const secretLike = createEmptyCompatibilityMatrix();
    const secretRecord = secretLike.records[0] as unknown as {stages: Record<string, unknown>};
    secretRecord.stages = {...secretRecord.stages, install: {status: 'fail', detail: 'api_key=sk-test'}};
    expect(validateCompatibilityMatrix(secretLike)).toEqual(expect.objectContaining({valid: false}));
  });
});
