class Solution {
    public boolean isSelfCrossing(int[] dist) {
        int n = dist.length;
        if(n < 4) return false;
        int[] distance = new int[n+1];
        distance[0] = 0;
        for(int i = 0 ; i < n ; i++){
            distance[i+1] = dist[i];
        }
        for(int i = 3 ; i < distance.length ; i++){
            if(distance[i-3] >= distance[i-1] && distance[i-2] <= distance[i]) return true;

            if(i >= 5){
                if(distance[i-2] >= distance[i-4] && distance[i-3] >= distance[i-1] && distance[i-4]+distance[i] >= distance[i-2] && distance[i-1] + distance[i-5] >= distance[i-3]) return true;
            }
        }
        return false;
    }
}