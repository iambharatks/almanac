class Solution {
    public int countGroups(int[] position, int[] speed, int distance) {
        int n = speed.length;
        int gp = n;
        int mergableSpeed = speed[n-1];
        for(int i = n-1; i >= 1 ; i--){
            if(position[i]-position[i-1] <= distance) gp--;
            else if(speed[i-1] > mergableSpeed) gp--;
            else{
                mergableSpeed = speed[i-1];
            }
        }
        return gp;
    }
}