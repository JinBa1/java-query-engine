SELECT Student.A, Course.E, Enrolled.H
FROM Student, Enrolled, Course
WHERE Student.A = Enrolled.A AND Enrolled.E = Course.E
AND Student.C > 100 AND Course.G > 2;
