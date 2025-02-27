package codes.dreaming.dreamingQueue;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Evaluates mathematical formulas with variable substitution
 */
public class FormulaEvaluator {
    private final ScriptEngine engine;
    private final Logger logger;

    public FormulaEvaluator(Logger logger) {
        this.logger = logger;
        ScriptEngineManager manager = new ScriptEngineManager();
        this.engine = manager.getEngineByName("JavaScript");
    }

    /**
     * Evaluates a formula with the given variables.
     *
     * @param formula The formula string with variables in format %variable_name%
     * @param variables Map of variable names to their values
     * @param defaultValue Value to return if evaluation fails
     * @return The evaluated result as an integer, or defaultValue if the evaluation fails
     */
    public int evaluate(String formula, Map<String, Object> variables, int defaultValue) {
        try {
            // Replace variables in the formula
            String processedFormula = formula;
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                processedFormula = processedFormula.replace("%" + entry.getKey() + "%", entry.getValue().toString());
            }
            
            // Evaluate the formula
            Object result = engine.eval(processedFormula);
            
            // Convert result to int
            if (result instanceof Number) {
                return ((Number) result).intValue();
            } else {
                throw new ScriptException("Result is not a number: " + result);
            }
        } catch (Exception e) {
            logger.warning("Failed to evaluate formula: " + formula + ". Error: " + e.getMessage());
            return defaultValue;
        }
    }
}