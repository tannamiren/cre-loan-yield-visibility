type ScoreBreakdownProps = {
  scoreType: number;
  scoreTime: number;
  scoreSize: number;
};

export function ScoreBreakdown({ scoreType, scoreTime, scoreSize }: ScoreBreakdownProps) {
  return (
    <div className="flex gap-1 text-xs text-gray-600">
      <span className="rounded bg-gray-100 px-1.5 py-0.5" title="Type score">T{scoreType}</span>
      <span className="rounded bg-gray-100 px-1.5 py-0.5" title="Time-to-maturity score">M{scoreTime}</span>
      <span className="rounded bg-gray-100 px-1.5 py-0.5" title="Loan-size score">S{scoreSize}</span>
    </div>
  );
}
