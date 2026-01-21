package com.study.service;

import lombok.Data;

@Data
public class AnswerStatistic {
    private Long questionId;
    private Integer planId;
    private int totalStudents;
    private int answeredCount;
    private int notAnsweredCount;
    private int aCount;
    private int bCount;
    private int cCount;
    private int dCount;
    private double aRatio;
    private double bRatio;
    private double cRatio;
    private double dRatio;
    private double notAnsweredRatio;
}
