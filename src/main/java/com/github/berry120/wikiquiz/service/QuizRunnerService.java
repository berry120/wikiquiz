package com.github.berry120.wikiquiz.service;

import com.github.berry120.wikiquiz.model.Quiz;
import com.github.berry120.wikiquiz.model.QuizQuestion;
import com.github.berry120.wikiquiz.model.client.ClientAnswer;
import com.github.berry120.wikiquiz.model.client.ClientFakeAnswerRequest;
import com.github.berry120.wikiquiz.model.client.ClientPlayerJoined;
import com.github.berry120.wikiquiz.model.client.ClientPlayerRemoved;
import com.github.berry120.wikiquiz.model.client.ClientQuestion;
import com.github.berry120.wikiquiz.model.client.ClientResult;
import com.github.berry120.wikiquiz.model.client.ClientScore;
import com.github.berry120.wikiquiz.model.client.PlayerDetails;
import com.github.berry120.wikiquiz.redis.RedisRepository;
import com.github.berry120.wikiquiz.socket.DisplaySocket;
import com.github.berry120.wikiquiz.socket.PhoneSocket;
import com.github.berry120.wikiquiz.socket.RootSocket;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class QuizRunnerService {

    private final DisplaySocket displaySocket;
    private final PhoneSocket phoneSocket;
    private final RootSocket rootSocket;
    private final RedisRepository redisRepository;
    private final AnswerTransformerService answerTransformer;

    @Inject
    QuizRunnerService(RedisRepository redisRepository, DisplaySocket displaySocket, PhoneSocket phoneSocket, RootSocket rootSocket, AnswerTransformerService answerTransformer) {
        this.redisRepository = redisRepository;
        this.displaySocket = displaySocket;
        this.phoneSocket = phoneSocket;
        this.rootSocket = rootSocket;
        this.answerTransformer = answerTransformer;
    }

    public void startQuiz(String quizId) {
        nextQuestionOrFinish(quizId);
    }

    public boolean quizExists(String quizId) {
        return redisRepository.quizExists(quizId);
    }

    public void addFakeAnswer(String quizId, PlayerDetails playerDetails, String fakeAnswer) {
        redisRepository.storeFakeAnswer(quizId, playerDetails, fakeAnswer);
        if (redisRepository.haveAllFakeAnswers(quizId)) {
            sendQuestionStage(quizId);
        }
    }

    public void addAnswer(String quizId, PlayerDetails playerDetails, String answer) {
        redisRepository.storeAnswer(quizId, playerDetails, answer);
        if (redisRepository.haveAllAnswers(quizId)) {
            sendResultsStage(quizId);
        }
    }

    public void nextQuestionOrFinish(String quizId) {
        int questionNumber = redisRepository.retrieveQuestionNumber(quizId);
        if (questionNumber + 1 >= redisRepository.retrieveQuiz(quizId).getQuestions().size()) {
            sendFinalScoreStage(quizId);
        } else {
            sendFakeQuestionStage(quizId);
        }
    }

    private void sendFakeQuestionStage(String quizId) {
        redisRepository.removeTempQuestionData(quizId);
        int questionNumber = redisRepository.retrieveQuestionNumber(quizId) + 1;
        redisRepository.storeQuestionNumber(quizId, questionNumber);

        Quiz quiz = redisRepository.retrieveQuiz(quizId);
        String questionText = quiz.getQuestions().get(questionNumber).getQuestion();
        ClientFakeAnswerRequest clientFakeAnswerRequest = new ClientFakeAnswerRequest(questionText, questionNumber);

        displaySocket.sendObject(quizId, clientFakeAnswerRequest);
        phoneSocket.sendObject(quizId, clientFakeAnswerRequest);
    }

    public void sendQuestionStage(String quizId) {
        Quiz quiz = redisRepository.retrieveQuiz(quizId);
        QuizQuestion question = quiz.getQuestions().get(redisRepository.retrieveQuestionNumber(quizId));
        Set<String> questionOptions = new HashSet<>(redisRepository.retrieveFakeAnswers(quizId).values());
        for (String sampleWrongAnswer : question.getSampleWrongAnswers()) {
            if (questionOptions.size() < 3) {
                questionOptions.add(sampleWrongAnswer);
            }
        }
        questionOptions.add(question.getCorrectAnswer());

        ClientQuestion clientQuestion = new ClientQuestion(question.getQuestion(), questionOptions);

        displaySocket.sendObject(quizId, clientQuestion);
        phoneSocket.sendObject(quizId, clientQuestion);
    }

    public void sendResultsStage(String quizId) {
        redisRepository.updateScores(quizId);

        int questionIdx = redisRepository.retrieveQuestionNumber(quizId);
        QuizQuestion question = redisRepository.retrieveQuiz(quizId).getQuestions().get(questionIdx);

        ClientAnswer clientAnswer = new ClientAnswer(
                question.getCorrectAnswer(),
                questionIdx,
                answerTransformer.answersToClientFormat(redisRepository.retrieveAnswers(quizId).entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey().getName(), Map.Entry::getValue))),
                answerTransformer.answersToClientFormat(redisRepository.retrieveFakeAnswers(quizId).entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey().getName(), Map.Entry::getValue))),
                redisRepository.retrieveScores(quizId)
        );

        displaySocket.sendObject(quizId, clientAnswer);
        phoneSocket.sendObject(quizId, clientAnswer);
    }

    private void sendFinalScoreStage(String quizId) {
        ClientResult clientResult = new ClientResult(redisRepository.retrieveScores(quizId)
                .entrySet().stream()
                .map(e -> new ClientScore(e.getKey(), e.getValue()))
                .collect(Collectors.toList()));

        displaySocket.sendObject(quizId, clientResult);
        phoneSocket.sendObject(quizId, clientResult);
    }

    public boolean addPlayer(String quizId, PlayerDetails playerDetails) {
        boolean storedOk = redisRepository.storePlayer(quizId, playerDetails);
        if (storedOk) {
            rootSocket.sendObject(quizId, new ClientPlayerJoined(playerDetails.getName()));
        }
        return storedOk;
    }

    public void removePlayer(String quizId, PlayerDetails playerDetails) {
        redisRepository.removePlayer(quizId, playerDetails);
        rootSocket.sendObject(quizId, new ClientPlayerRemoved(playerDetails.getName()));
    }

}
