export type Difficulty = "all" | "easy" | "medium" | "hard";
export type Verdict = "REAL" | "FAKE";
export type Confidence = "LOW" | "MEDIUM" | "HIGH";

export interface AnalysisSummary {
  id: string;
  filename: string;
  status: "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED";
  stage: string;
  created_at: string;
  updated_at: string;
  expires_at: string;
}

export interface AnalysisDetail extends AnalysisSummary {
  result: null | {
    spatial: number;
    temporal: number;
    fused: number;
    n_faces: number;
    family: string;
    faithfulness: string;
    model_version: string;
    calibration_version: string;
    warnings: string[];
    is_demo: boolean;
    metadata: Record<string, unknown>;
    policy: null | {
      verdict: Verdict;
      traffic_light: "RED" | "YELLOW" | "GREEN";
      reason: string;
      primary_signal: string;
      signal_score: number;
      score_interpretation: string;
      policy_version: string;
      thresholds: Record<string, number>;
    };
  };
  error: null | { code?: string; message?: string; details?: Record<string, unknown> };
  artifacts: { kind: string; mime_type: string; url: string }[];
}

export interface GameSummary {
  id: string;
  status: "ACTIVE" | "COMPLETED" | "CANCELLED";
  total_rounds: number;
  answered_rounds: number;
  next_round: number | null;
  difficulty: Difficulty;
  score: number;
  max_streak: number;
  created_at?: string;
  updated_at?: string;
}

export interface GameRound {
  round: number;
  video_url: string;
  answered: boolean;
  result?: AnswerReveal | null;
}

export interface AnswerReveal {
  round: number;
  answer: Verdict | null;
  ground_truth: Verdict;
  user_correct: boolean | null;
  ai_correct: boolean | null;
  ai: null | { verdict?: Verdict; spatial?: number | null; temporal?: number | null; fused?: number | null; model_version?: string };
  explanation: string;
  cues: string[];
  tips: string[];
  technique: string | null;
  points: number;
  streak: number;
  skipped?: boolean;
}

export interface GameResult {
  id?: string;
  game_id?: string;
  status?: string;
  total_rounds: number;
  score: number;
  max_streak: number;
  grade: string;
  average_time_seconds: number | null;
  skipped_rounds: number;
  played_rounds: number;
  ai_evaluated_rounds: number;
  user_score?: number;
  ai_score: number | null;
  user_accuracy: number | null;
  ai_accuracy: number | null;
  difficulty?: Difficulty;
  rounds: AnswerReveal[];
}
