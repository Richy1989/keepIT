// Original outlines in a 28-point drawing square. Subpaths share a single
// rounded stroke, including interior marks, so intersections do not darken.
function ring(x, y, r) {
    return " M" + (x-r) + " " + y + "a" + r + " " + r + " 0 1 0 " +
        (2*r) + " 0a" + r + " " + r + " 0 1 0 " + (-2*r) + " 0Z";
}
function fleck(x, y) { return " M" + x + " " + y + "l0.02 0"; }

var motifs = {
    groceries: {
        bag: "M5 9H23L25 25H3Z M10 12V7a4 4 0 0 1 8 0v5",
        basket: "M2 12H26L23 24H5Z M7 12l6-9 M21 12l-6-9 M10 16l1 4 M17 16l-1 4",
        apple: "M14 9C6 3 1 11 5 20Q9 28 14 24Q20 28 24 19C28 10 22 5 14 9Z M14 9Q13 3 18 2",
        carrot: "M4 25Q5 17 12 9Q16 5 20 9Q24 13 19 17Q12 23 4 25Z M19 9l1-7 M21 10l5-4 M10 14l3 2",
        carton: "M7 3H19V7L23 12V25H6V12L9 7Z M9 7H19 M6 12H17L19 7 M17 12V25"
    },
    code: {
        brackets: "M10 6L2 14L10 22 M18 6l8 8-8 8",
        braces: "M10 3Q6 3 6 7V10Q6 14 2 14Q6 14 6 18V21Q6 25 10 25 M18 3Q22 3 22 7V10Q22 14 26 14Q22 14 22 18V21Q22 25 18 25",
        slash: "M8 25L20 3",
        semicolon: ring(15,6,1.7) + " M16 17C14 14 11 17 13 19Q14 20 16 19Q16 23 11 25",
        terminal: "M4 5l9 9-9 9 M15 23h10"
    },
    work: {
        laptop: "M5 20V5H23V20 M5 20H23L27 24H1Z",
        mug: "M4 7H20V18Q20 25 12 25Q4 25 4 18Z M20 9H22C29 9 27 18 20 18",
        paperclip: "M9 17l9-10c3-3 7 1 4 4L10 24c-5 5-12-2-7-7L16 3 M7 19l9-10",
        calendar: "M4 6H24V25H4Z M4 12H24 M9 3v6 M19 3v6 M9 17h3 M16 20h3",
        check: "M3 15l8 8L25 5"
    },
    study: {
        book: "M14 8Q9 3 2 5V22Q8 20 14 25Q20 20 26 22V5Q20 3 14 8Z M14 8V25",
        pencil: "M3 25l2-8L19 3Q20 2 22 4l2 2q2 2 0 4L11 23Z M5 17l6 6 M16 6l6 6",
        ruler: "M3 19L20 2l6 7L9 26Z M15 7l3 3 M10 12l3 3 M6 16l3 3",
        bulb: "M10 19C10 15 5 15 5 10a9 9 0 0 1 18 0c0 5-5 6-5 10v1h-8Z M11 25h6",
        cap: "M1 10l13-7 13 7-13 7Z M6 13v6q8 8 16 0v-6 M26 11v9"
    },
    money: {
        coins: ring(11,11,8) + " M19 10c8 0 10 12 2 15q-8 3-11-5 M11 7v8",
        banknotes: "M2 7l21-3 2 15-21 3Z M7 25l20-3" + ring(13,13,3.2),
        pig: "M3 14q0-6 8-7l5-4 1 5q4 1 5 5h4v6l-4 1-2 5h-4l-1-3h-5l-1 3H6l-1-6q-3-1-3-5Z M3 13Q0 13 1 9" + fleck(19,12),
        percent: ring(7,7,4) + " M5 25L23 3" + ring(21,21,4),
        chart: "M3 24V5 M3 24h22 M7 18l6-7 4 3 8-10 M19 4h6v6"
    },
    coffee: {
        espresso: "M3 12h17v4c0 6-3 9-8.5 9S3 22 3 16Z M20 13h2c5 0 4 6-1 7 M8 8C5 6 11 5 9 2 M15 8c-2-2 3-3 1-6",
        bean: "M5 21C0 15 8 2 17 2c8 0 11 7 7 14S10 28 5 21Z M6 21C17 21 10 8 21 5",
        croissant: "M2 20q-2-7 4-11q0-4 6-4q5-4 9 1q5 0 5 7q3 5-1 10l-6-7q-5-4-10 1l-6 6Z M6 9q5 1 6 6 M12 5q4 3 4 9 M21 6q1 5-2 10"
    },
    food: {
        pizza: "M3 6Q15 0 26 9L12 26Z M5 10Q15 5 23 12" + ring(13,14,1.7),
        burger: "M3 12C4 0 24 0 25 12Z M2 16q3-3 7 0t7 0t9 0 M3 20h22q-1 6-7 6H10q-6 0-7-6Z",
        cutlery: "M4 3v7q0 4 4 4t4-4V3 M8 3v22 M21 25V3q-6 5-5 13h5",
        noodles: "M2 14h24q-1 10-12 11Q4 24 2 14Z M6 13q1-6 5-2t7 0 M15 9l9-7 M18 10l9-5",
        taco: "M2 21C0 4 18-1 25 15q2 4 1 7H4Z M3 20q2-6 4-6q0-4 4-4q3-4 6-1q4-2 6 2"
    },
    baking: {
        whisk: "M4 24q-2-2 0-4l7-7 4 4-7 7q-2 2-4 0Z M11 13C10 7 19 0 23 4s-2 14-8 13 M13 15l9-10",
        rollingPin: "M5 16L17 4l7 7-12 12Z M7 18l-4 4 3 3 4-4 M19 6l4-4 3 3-4 4",
        cupcake: "M4 14h20l-3 11H7Z M4 14q-3-6 4-7q4 0 5-5q7 1 7 6q7 0 4 6 M10 18l1 4 M17 18l-1 4",
        cookie: "M24 14q-6 1-6-5q-6 0-5-6C1 3-1 18 7 24q11 8 18-5q1-3-1-5Z"+ring(9,11,1.2)+ring(15,20,1.3),
        measure: "M3 6h17v18H6L5 10Z M20 9h3q6 4-1 9h-2 M7 12h5 M8 17h4"
    },
    cocktails: {
        martini: "M2 8h24L14 20Z M14 20v6 M8 26h12 M17 10l7-9" + ring(20,6,2.6),
        tumbler: "M4 4h20l-2 21H6Z M6 16q5-3 10 0t7 0 M9 9l5-2 2 5-5 2Z",
        citrus: ring(14,14,11) + " M8 8l3 3 M20 8l-3 3 M8 20l3-3 M20 20l-3-3 M14 5v4 M14 19v4",
        parasol: "M2 13Q5 3 14 3q9 0 12 10q-4-3-8 0q-4-3-8 0q-4-3-8 0Z M14 3q-4 4-4 10 M14 13v12",
        wine: "M8 3h12l2 8q1 7-8 8q-9-1-8-8Z M7 11q3-2 7 0t7 0 M14 19v7 M8 26h12"
    },
    fruit: {
        cherries: ring(7,20,5) + ring(21,18,5) + " M7 15Q15 11 15 2Q18 5 21 13 M15 3q7-3 10 2q-5 3-10-2Z",
        pear: "M12 6q3-4 6 0q0 5 4 10q6 10-8 10Q1 26 5 17q6-7 7-11Z M15 5q0-4 4-4",
        lemon: "M4 16C4 7 13 2 20 5q4-2 4 2q-1 2 1 4c1 9-10 16-17 12q-6 2-5-3Z",
        berry: "M5 10q9-6 18 0q2 5-6 14q-3 4-6 0Q3 15 5 10Z M8 5l5 2 2-5 2 5 4-2 M10 13l1 1 M17 15l-1 1 M13 20v1",
        melon: "M2 9l23-5Q27 22 10 25Z M5 14q11 4 17-9 M10 11l1 1 M16 9v1"
    },
    sport: {
        football: ring(14,14,11.5) + " M14 8l6 4-2 6h-8l-2-6Z M14 8V3 M20 12l5-2 M18 18l3 5 M10 18l-3 5 M8 12l-5-2",
        basketball: ring(14,14,11.5) + " M6 6l16 16 M6 22L22 6 M3 12q8 1 9-9 M16 25q1-8 9-9",
        racket: "M11 17C6 12 12 2 19 2s9 7 4 13q-6 6-12 2Z M11 17l-8 9-2-2 8-9 M14 7l6 6 M13 13l6-7" + ring(22,23,3),
        dumbbell: "M3 8h5v12H3Z M8 10h3v8H8 M11 13h6 M17 10h3v8h-3Z M20 8h5v12h-5Z",
        stopwatch: ring(14,16,9.5) + " M14 6V2 M10 2h8 M21 8l3-3 M14 16l4-5"
    },
    running: {
        shoe: "M3 12l4-7q4 5 8 2l2 5q2 4 8 5l1 5q-10 3-24-3Z M3 16q13 6 22 3 M13 10l3-1 M15 13l3-1",
        flag: "M5 26V4q5-4 11-1q4 2 9 0v13q-4 2-9 0q-6-3-11 0 M12 3v10 M18 5v11 M6 10q4-3 10-1t8 0",
        pulse: "M1 16h6l4-11 5 20 4-12 2 3h5",
        bottle: "M10 2h8v5l3 4v12q0 3-3 3h-8q-3 0-3-3V11l3-4Z M10 7h8 M7 13h14 M7 21h14",
        medal: ring(14,19,7) + " M10 13L4 2h7l4 8 M16 13l8-11h-7l-4 6 M12 18l2-1v5"
    },
    cycling: {
        bike: ring(6,19,5) + ring(22,19,5) + " M6 19l6-10 5 10H6 M12 9h7 M16 19l4-9 M22 19L18 4h-3 M9 7h5",
        helmet: "M2 17C2 6 12 0 21 7q5 4 5 11l-8 1-5 5-3-5Z M5 10q5-1 8-5 M12 12q5-2 7-5 M13 19l4-3h8",
        peaks: "M1 23l10-17 6 10 4-7 6 14Z M7 13l4 2 3-3",
        trail: "M2 23l2-1 M8 21l2-1 M13 17l-1-2 M9 11l1-2 M14 7l2-1 M21 5l3-1"
    },
    yoga: {
        lotus: "M14 22Q3 23 2 11q7 1 12 9q4-8 12-9q-1 12-12 11Z M9 13q1-6 5-10q4 4 5 10",
        mat: "M6 11q-5 0-5 6t6 6h15q5 0 5-6t-6-6Z M6 11q5 0 5 6t-5 6 M5 15q3-1 3 2t-3 1 M14 11l3-5h9l-3 5",
        leaf: "M4 22C0 13 8 4 24 3q0 18-12 20q-4 1-8-1Z M3 25l15-16",
        sun: ring(14,14,6) + " M14 2v3 M14 23v3 M2 14h3 M23 14h3 M5 5l2 2 M21 21l2 2 M5 23l2-2 M21 7l2-2",
        stones: "M2 22q0-5 11-5t13 5q0 4-12 4T2 22Z M6 14q0-4 9-5q7-1 7 4q0 4-8 4t-8-3Z M10 7q0-4 7-5q4 0 3 4q-1 3-6 3t-4-2Z"
    },
    party: {
        balloon: "M6 9a8 8 0 1 1 16 0q0 6-8 10Q6 15 6 9Z M14 19l-2 3h4Z M14 22c4 5-5 2-4 6",
        confetti: "M3 11l3-5 4 5Z M15 22q1-5 4-2t5-3" + fleck(20,5) + fleck(5,23) + fleck(13,15),
        popper: "M3 25l5-17q7 1 12 11Z M6 17l6 5 M13 11q-1-5 4-7 M18 13l7-5 M22 16l4 1",
        streamer: "M2 21q5 4 9-3c5-9-6-11-7-5c-1 5 10 5 13-2s-3-10-5-5c-2 5 8 6 14-2"
    },
    birthday: {
        cake: "M3 25h22 M5 24V13h18v11 M5 16q3 5 6 0q3 5 6 0q3 5 6 0 M9 12V7 M15 12V6 M20 12V7 M9 4l1-2 M15 3l1-2 M20 4l1-2",
        gift: "M4 13h20v12H4Z M2 8h24v5H2Z M14 9v16 M14 8C4 10 4 0 9 2q3 1 5 6q3-9 8-6q4 4-8 6Z",
        hat: "M3 23l11-20 11 20q-10 5-22 0Z M7 16l12-4 M5 21l17-4",
        candle: "M9 11h10v15H9Z M14 9q-6-2 1-9q5 8-1 9Z M10 18l8-4 M10 23l8-4",
        star: "M14 2l4 8 9 1-7 7 2 9-8-5-8 5 2-9-7-7 9-1Z"
    },
    celebration: {
        toast: "M3 5l8-2 2 9q1 5-3 6q-4 1-5-4Z M10 18l2 6 M8 25l8-2 M18 4l8 2-3 9q-2 5-5 3q-4-1-2-6Z M18 18l-2 6 M13 23l7 3",
        cork: "M6 8q-2-3 3-5t8 0l2 4q2 2-1 4l6 10q0 3-5 5q-3 1-4-1l-6-12q-2 0-3-5Z M8 12l10-4 M15 18l2-1",
        bubbles: ring(8,19,5) + ring(19,11,4) + ring(10,4,2.3),
        sparkle: "M14 2q1 10 11 12q-10 1-11 12Q12 16 2 14Q12 12 14 2Z",
        disco: ring(14,16,10) + " M14 1v5 M14 6q-9 9 0 20q9-11 0-20Z M5 12q9 4 18 0 M5 20q9 3 18 0"
    },
    travel: {
        plane: "M3 17l9-6V5q2-6 4 0v6l10 6v3l-10-3v5l4 3v1l-6-2-6 2v-1l4-3v-5l-9 3Z",
        case: "M6 7h16q2 0 2 3v12q0 2-2 2H6q-2 0-2-2V10q0-3 2-3Z M10 7V3h8v4 M9 11v9 M19 11v9 M8 24v2 M20 24v2",
        camera: "M3 9h5l2-4h8l2 4h5v15H3Z" + ring(14,16,4.5),
        pin: "M14 26Q4 16 4 11a10 10 0 1 1 20 0q0 5-10 15Z" + ring(14,11,3.2),
        compass: ring(14,14,11.5) + " M8 20l4-9 8-4-4 10Z"
    },
    beach: {
        sunshine: ring(14,14,6) + " M14 2v3 M14 23v3 M2 14h3 M23 14h3 M5 5l2 2 M21 21l2 2 M5 23l2-2 M21 7l2-2",
        palm: "M9 26q6-7 6-17q3 8-1 17Z M15 9Q8-1 2 9q8-2 13 0Q7 8 5 17q4-4 10-8Q14-1 22 2q-5 2-7 7q9-8 12 2q-6-3-12-2Z",
        shell: "M10 23Q0 16 2 11q0-4 4-4q1-5 6-4q4-3 7 1q5-1 6 4q5 5-1 10l-6 5v2h-8Z M7 9l5 11 M14 5v14 M21 9l-5 11",
        starfish: "M14 2q2 0 3 9l9-2q3 2-6 8l3 8q-2 2-10-5l-8 5q-2-1 3-10L1 9q0-2 10 0Z",
        surf: "M1 18q5 1 8-6q3-8 11-6q-6 4-3 9q2 4 9 2 M3 22q5 2 10-1q5 3 11 0"
    },
    garden: {
        leaves: "M3 25Q7 16 18 9 M8 18Q3 11 5 4q9 3 8 10 M11 15q2-11 14-12q1 13-12 14",
        sprout: "M14 25V14 M14 17Q3 17 2 6q11-1 12 11Z M14 14Q13 3 26 2q0 12-12 12Z M8 25h12",
        flower: "M11 9C3 1 14-3 15 5c5-9 13 0 5 4c10 2 4 11-2 6c1 9-10 9-8 1c-8 5-12-6 1-7Z M14 20v7q6-1 8-6Z" + ring(14,11,2),
        can: "M8 11h13v13H8Z M9 11q0-10 8-8q5 1 4 8 M8 15L2 10l-1 3 7 9 M21 12q7-4 6 2q-1 5-6 6",
        pot: "M5 15h18v4H5Z M7 19l2 7h10l2-7 M14 15V8q1-6 8-7q0 8-8 10 M14 13Q5 12 5 5q8 0 9 8Z"
    },
    pets: {
        paw: "M6 21q0-3 5-8q3-4 6 0l4 6q2 6-3 5q-4-2-7 0q-5 2-5-3Z" +
            ring(4.5,12,2.1) + ring(10,6,2.2) + ring(17,5.5,2.2) + ring(23,11,2.1),
        bone: "M3 20q-2-6 4-6l8-8q0-6 5-5q4 1 3 5q6 0 4 5q-1 4-6 3l-9 8q1 6-5 5q-4 0-3-5Z",
        fish: "M2 14q8-14 18-3l6-5v16l-6-5Q10 28 2 14Z M9 8q4 6 0 13" + fleck(6,13),
        yarn: ring(13,12,9.5) + " M6 5q7 1 13 9 M4 9q7 0 12 9 M7 18q10-4 13-11 M13 22q1 6 6 2t7 0"
    }
};
