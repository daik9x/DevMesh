package devmesh.evaluation;
public record ScoreDimension(double rawScore, double weight) { public double weightedScore() { return rawScore * weight; } }